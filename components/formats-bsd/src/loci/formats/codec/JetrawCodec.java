/*
 * #%L
 * BSD implementations of Bio-Formats readers and writers
 * %%
 * Copyright (C) 2022 Dotphoton AG:
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package loci.formats.codec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.MissingLibraryException;

/**
 */
public class JetrawCodec extends BaseCodec {

  private static boolean isInitialized = false;

  private String getJetrawLibraryName() throws MissingLibraryException {
    String operatingSystem = System.getProperty("os.name").toLowerCase();
    String libraryName = "";
    if (operatingSystem.contains("win")) {
        libraryName = "jetraw_bioformats_plugin.dll";
    } else if (operatingSystem.contains("mac")) {
        libraryName = "libjetraw_bioformats_plugin.dylib";
        // FIXME: apparently needed in macOS
        Locale.setDefault(new Locale("en", "US"));
    } else if (operatingSystem.contains("nix") || operatingSystem.contains("nux") || operatingSystem.contains("aix")) {
        libraryName = "libjetraw_bioformats_plugin.so";
    } else {
        throw new MissingLibraryException("Could not determine OS to load jetraw dynamic library.");
    }
    return libraryName;
  }

  // load jetraw JNI dynamic library from JAR file
  private void loadLib(String name) throws MissingLibraryException {
    LOGGER.debug("[JETRAW] loading JNI Jetraw library.");
    InputStream in = JetrawCodec.class.getResourceAsStream(name);
    byte[] buffer = new byte[1024];
    int read = -1;
    try {
        File temp = File.createTempFile(name, "");
        FileOutputStream fos = new FileOutputStream(temp);

        while((read = in.read(buffer)) != -1) {
            fos.write(buffer, 0, read);
        }
        fos.close();
        in.close();
        System.load(temp.getAbsolutePath());
        LOGGER.debug("[JETRAW] JNI Jetraw library loaded successfully.");
    } catch (Exception e) {
        throw new MissingLibraryException("JNI Jetraw library not available", e);
    }
    // initialize dpcore
    dpcoreInit();
    isInitialized = true;
  }

  private byte[] convertShortArrayToByteArray(short[] shortBuffer, CodecOptions options) {
    int pixels = options.width*options.height;
    // convert short buffer into a byte buffer
    int short_index = 0;
    int byte_index = 0;
    byte[] byteBuf = new byte[2*pixels];
    for(/*NOP*/; short_index != pixels; /*NOP*/) {
      if (options.littleEndian) {
        byteBuf[byte_index]     = (byte) (shortBuffer[short_index] & 0x00FF);
        byteBuf[byte_index + 1] = (byte) ((shortBuffer[short_index] & 0xFF00) >> 8);
      } else {
        byteBuf[byte_index]     = (byte) ((shortBuffer[short_index] & 0xFF00) >> 8);
        byteBuf[byte_index + 1] = (byte) (shortBuffer[short_index] & 0x00FF);
      }
      ++short_index; byte_index += 2;
    }
    return byteBuf;
  }

  // -- Native JNI calls --
  
  public native int dpcoreInit();
  public native int performPreparation(short[] buf, long imgsize, String identifier, float error_bound);
  public native int performEncoding(short[] buf, int width, int height, byte[] out);
  public native void performDecoding(byte[] buf, int bufSize, short[] out, int outSize);

  // -- Codec API methods --

  /* @see Codec#compress(byte[], CodecOptions) */
  @Override
  public byte[] compress(byte[] data, CodecOptions options) throws MissingLibraryException, FormatException {
    if (!isInitialized) {
      // load JNI library
      loadLib(getJetrawLibraryName());
    }
    
    // create short buffer for input image
    int pixels = options.width*options.height;
    short[] inputBuffer = new short[pixels];

    // convert input image data (byte[]) into expected short[] by Jetraw
    if (options.littleEndian) {
      ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(inputBuffer);
    } else {
      ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(inputBuffer);
    }

    LOGGER.debug("[JETRAW] preparation using identifier: " + options.jetrawIdentifier);
    byte[] encodedBuffer = new byte[(int)(pixels*0.5)];
    int status = performPreparation(inputBuffer, pixels, options.jetrawIdentifier, 1.0f);
    LOGGER.debug("[JETRAW] preparation exited with status: " + String.valueOf(status));
    int imageLength = performEncoding(inputBuffer, options.width, options.height, encodedBuffer);
    LOGGER.debug("[JETRAW] encoding exited with image length: " + String.valueOf(imageLength));

    // FIXME: find a way to optimize this extra copy
    return Arrays.copyOfRange(encodedBuffer, 0, imageLength);
  }

  /* @see Codec#decompress(RandomAccessInputStream, CodecOptions) */
  @Override
  public byte[] decompress(RandomAccessInputStream in, CodecOptions options) throws FormatException, IOException {
    byte[] inputBuffer = new byte[(int) in.length()];
    in.read(inputBuffer);
    return decompress(inputBuffer, options);
  }

  @Override
  public byte[] decompress(byte[] inputBuffer, CodecOptions options) throws FormatException {
    if (!isInitialized) {
      // load JNI library
      loadLib(getJetrawLibraryName());
    }

    // allocate decoded buffer and native call to encode buffer with Jetraw
    short[] decodedBuffer = new short[options.width*options.height];
    LOGGER.debug("[JETRAW] performing decoding.");
    performDecoding(inputBuffer, inputBuffer.length, decodedBuffer, options.width*options.height);
    
    return convertShortArrayToByteArray(decodedBuffer, options);
  }
}
