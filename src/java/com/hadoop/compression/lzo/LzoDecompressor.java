/*
 * This file is part of Hadoop-Gpl-Compression.
 *
 * Hadoop-Gpl-Compression is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Hadoop-Gpl-Compression is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Hadoop-Gpl-Compression.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hadoop.compression.lzo;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.compress.Decompressor;

/**
 * A {@link Decompressor} based on the lzo algorithm.
 * http://www.oberhumer.com/opensource/lzo/
 * 
 */
class LzoDecompressor implements Decompressor {
  private static final Log LOG = 
    LogFactory.getLog(LzoDecompressor.class.getName());

  // HACK - Use this as a global lock in the JNI layer
  @SuppressWarnings({ "unchecked", "unused" })
  private static Class clazz = LzoDecompressor.class;
  
  private int directBufferSize;
  private Buffer compressedDirectBuf = null;
  private int compressedDirectBufLen;
  private Buffer uncompressedDirectBuf = null;
  private byte[] userBuf = null;
  private int userBufOff = 0, userBufLen = 0;
  private boolean finished;
  
  private CompressionStrategy strategy;
  @SuppressWarnings("unused")
  private long lzoDecompressor = 0;   // The actual lzo decompression function.
  
  public static enum CompressionStrategy {
    /**
     * lzo1 algorithms.
     */
    LZO1 (0),

    /**
     * lzo1a algorithms.
     */
    LZO1A (1),

    /**
     * lzo1b algorithms.
     */
    LZO1B (2),
    LZO1B_SAFE(3),

    /**
     * lzo1c algorithms.
     */
    LZO1C (4),
    LZO1C_SAFE(5),
    LZO1C_ASM (6),
    LZO1C_ASM_SAFE (7),

    /**
     * lzo1f algorithms.
     */
    LZO1F (8),
    LZO1F_SAFE (9),
    LZO1F_ASM_FAST (10),
    LZO1F_ASM_FAST_SAFE (11),
    
    /**
     * lzo1x algorithms.
     */
    LZO1X (12),
    LZO1X_SAFE (13),
    LZO1X_ASM (14),
    LZO1X_ASM_SAFE (15),
    LZO1X_ASM_FAST (16),
    LZO1X_ASM_FAST_SAFE (17),
    
    /**
     * lzo1y algorithms.
     */
    LZO1Y (18),
    LZO1Y_SAFE (19),
    LZO1Y_ASM (20),
    LZO1Y_ASM_SAFE (21),
    LZO1Y_ASM_FAST (22),
    LZO1Y_ASM_FAST_SAFE (23),
    
    /**
     * lzo1z algorithms.
     */
    LZO1Z (24),
    LZO1Z_SAFE (25),
    
    /**
     * lzo2a algorithms.
     */
    LZO2A (26),
    LZO2A_SAFE (27);
    
    private final int decompressor;

    private CompressionStrategy(int decompressor) {
      this.decompressor = decompressor;
    }
    
    int getDecompressor() {
      return decompressor;
    }
  }; // CompressionStrategy
  
  private static boolean nativeLzoLoaded;
  public static final int LZO_LIBRARY_VERSION;
  
  static {
    if (GPLNativeCodeLoader.isNativeCodeLoaded()) {
      // Initialize the native library
      try {
        initIDs();
        nativeLzoLoaded = true;
      } catch (Throwable t) {
        // Ignore failure to load/initialize native-lzo
        LOG.warn(t.toString());
        nativeLzoLoaded = false;
      }
      LZO_LIBRARY_VERSION = (nativeLzoLoaded) ? 0xFFFF & getLzoLibraryVersion()
                                              : -1;
    } else {
      LOG.error("Cannot load " + LzoDecompressor.class.getName() + 
                " without native-hadoop library!");
      nativeLzoLoaded = false;
      LZO_LIBRARY_VERSION = -1;
     }
   }
  
  /**
   * Check if lzo decompressors are loaded and initialized.
   * 
   * @return <code>true</code> if lzo decompressors are loaded & initialized,
   *         else <code>false</code> 
   */
  public static boolean isNativeLzoLoaded() {
    return nativeLzoLoaded;
  }

  /**
   * Creates a new lzo decompressor.
   * 
   * @param strategy lzo decompression algorithm
   * @param directBufferSize size of the direct-buffer
   */
  public LzoDecompressor(CompressionStrategy strategy, int directBufferSize) {
    this.directBufferSize = directBufferSize;
    this.strategy = strategy;
    
    compressedDirectBuf = ByteBuffer.allocateDirect(directBufferSize);
    uncompressedDirectBuf = ByteBuffer.allocateDirect(directBufferSize);
    uncompressedDirectBuf.position(directBufferSize);
    
    /**
     * Initialize {@link #lzoDecompress}
     */
    init(this.strategy.getDecompressor());
  }
  
  /**
   * Creates a new lzo decompressor.
   */
  public LzoDecompressor() {
    this(CompressionStrategy.LZO1X, 64*1024);
  }

  public synchronized void setInput(byte[] b, int off, int len) {
    if (b == null) {
      throw new NullPointerException();
    }
    if (off < 0 || len < 0 || off > b.length - len) {
      throw new ArrayIndexOutOfBoundsException();
    }
  
    this.userBuf = b;
    this.userBufOff = off;
    this.userBufLen = len;
    
    setInputFromSavedData();
    
    // Reinitialize lzo's output direct-buffer 
    uncompressedDirectBuf.limit(directBufferSize);
    uncompressedDirectBuf.position(directBufferSize);
  }
  
  synchronized void setInputFromSavedData() {
    compressedDirectBufLen = userBufLen;
    if (compressedDirectBufLen > directBufferSize) {
      compressedDirectBufLen = directBufferSize;
    }

    // Reinitialize lzo's input direct-buffer
    compressedDirectBuf.rewind();
    ((ByteBuffer)compressedDirectBuf).put(userBuf, userBufOff, 
                                          compressedDirectBufLen);
    
    // Note how much data is being fed to lzo
    userBufOff += compressedDirectBufLen;
    userBufLen -= compressedDirectBufLen;
  }

  public synchronized void setDictionary(byte[] b, int off, int len) {
    // nop
  }

  public synchronized boolean needsInput() {
    // Consume remanining compressed data?
    if (uncompressedDirectBuf.remaining() > 0) {
      return false;
    }
    
    // Check if lzo has consumed all input
    if (compressedDirectBufLen <= 0) {
      // Check if we have consumed all user-input
      if (userBufLen <= 0) {
        return true;
      } else {
        setInputFromSavedData();
      }
    }
    
    return false;
  }

  public synchronized boolean needsDictionary() {
    return false;
  }

  public synchronized boolean finished() {
    // Check if 'lzo' says its 'finished' and
    // all uncompressed data has been consumed
    return (finished && uncompressedDirectBuf.remaining() == 0);
  }

  public synchronized int decompress(byte[] b, int off, int len) 
    throws IOException {
    if (b == null) {
      throw new NullPointerException();
    }
    if (off < 0 || len < 0 || off > b.length - len) {
      throw new ArrayIndexOutOfBoundsException();
    }
    
    int n = 0;
    
    // Check if there is uncompressed data
    n = uncompressedDirectBuf.remaining();
    if (n > 0) {
      n = Math.min(n, len);
      ((ByteBuffer)uncompressedDirectBuf).get(b, off, n);
      return n;
    }
    
    // Check if there is data to decompress
    if (compressedDirectBufLen <= 0) {
      return 0;
    }
    
    // Re-initialize the lzo's output direct-buffer
    uncompressedDirectBuf.rewind();
    uncompressedDirectBuf.limit(directBufferSize);

    // Decompress data
    n = decompressBytesDirect(strategy.getDecompressor());
    uncompressedDirectBuf.limit(n);

    // Set 'finished' if lzo has consumed all user-data
    if (userBufLen <= 0) {
      finished = true;
    }
    
    // Return atmost 'len' bytes
    n = Math.min(n, len);
    ((ByteBuffer)uncompressedDirectBuf).get(b, off, n);

    return n;
  }
  
  public synchronized void reset() {
    finished = false;
    compressedDirectBufLen = 0;
    uncompressedDirectBuf.limit(directBufferSize);
    uncompressedDirectBuf.position(directBufferSize);
    userBufOff = userBufLen = 0;
  }

  public synchronized void end() {
    // nop
  }

  protected void finalize() {
    end();
  }
  
  private native static void initIDs();
  private native static int getLzoLibraryVersion();
  private native void init(int decompressor);
  private native int decompressBytesDirect(int decompressor);
}
