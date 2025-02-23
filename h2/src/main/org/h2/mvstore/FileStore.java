/*
 * Copyright 2004-2022 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.h2.mvstore.cache.FilePathCache;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.encrypt.FileEncrypt;
import org.h2.store.fs.encrypt.FilePathEncrypt;

/**
 * The default storage mechanism of the MVStore. This implementation persists
 * data to a file. The file store is responsible to persist data and for free
 * space management.
 */
public class FileStore {

    /**
     * The number of read operations.
     */
    protected final AtomicLong readCount = new AtomicLong();

    /**
     * The number of read bytes.
     */
    protected final AtomicLong readBytes = new AtomicLong();

    /**
     * The number of write operations.
     */
    protected final AtomicLong writeCount = new AtomicLong();

    /**
     * The number of written bytes.
     */
    protected final AtomicLong writeBytes = new AtomicLong();

    /**
     * The free spaces between the chunks. The first block to use is block 2
     * (the first two blocks are the store header).
     */
    protected final FreeSpaceBitSet freeSpace =
            new FreeSpaceBitSet(2, MVStore.BLOCK_SIZE);

    /**
     * The file name.
     */
    private String fileName;

    /**
     * Whether this store is read-only.
     */
    private boolean readOnly;

    /**
     * The file size (cached).
     */
    protected long fileSize;

    /**
     * The file.
     */
    private FileChannel file;

    /**
     * The encrypted file (if encryption is used).
     */
    private FileChannel encryptedFile;

    /**
     * The file lock.
     */
    private FileLock fileLock;

    @Override
    public String toString() {
        return fileName;
    }

    /**
     * Read from the file.
     *
     * @param pos the write position
     * @param len the number of bytes to read
     * @return the byte buffer
     */
    public ByteBuffer readFully(long pos, int len) {
        ByteBuffer dst = ByteBuffer.allocate(len);
        DataUtils.readFully(file, pos, dst);
        readCount.incrementAndGet();
        readBytes.addAndGet(len);
        return dst;
    }

    /**
     * Write to the file.
     *
     * @param pos the write position
     * @param src the source buffer
     */
    public void writeFully(long pos, ByteBuffer src) {
        int len = src.remaining();
        fileSize = Math.max(fileSize, pos + len);
        DataUtils.writeFully(file, pos, src);
        writeCount.incrementAndGet();
        writeBytes.addAndGet(len);
    }

    /**
     * Try to open the file.
     *
     * @param fileName the file name
     * @param readOnly whether the file should only be opened in read-only mode,
     *            even if the file is writable
     * @param encryptionKey the encryption key, or null if encryption is not
     *            used
     */
    public void open(String fileName, boolean readOnly, char[] encryptionKey) {
        open(fileName, readOnly, encryptionKey == null ? null :
                fileChannel ->  new FileEncrypt(fileName, FilePathEncrypt.getPasswordBytes(encryptionKey), fileChannel));
    }

    public FileStore open(String fileName, boolean readOnly) {

        FileStore result = new FileStore();
        result.open(fileName, readOnly, encryptedFile == null ? null :
                fileChannel -> new FileEncrypt(fileName, (FileEncrypt)file, fileChannel));
        return result;
    }

    private void open(String fileName, boolean readOnly, Function<FileChannel,FileChannel> encryptionTransformer) {
        if (file != null) {
            return;
        }
        // ensure the Cache file system is registered
        FilePathCache.INSTANCE.getScheme();
        this.fileName = fileName;
        FilePath f = FilePath.get(fileName);
        FilePath parent = f.getParent();
        if (parent != null && !parent.exists()) {
            throw DataUtils.newIllegalArgumentException(
                    "Directory does not exist: {0}", parent);
        }
        if (f.exists() && !f.canWrite()) {
            readOnly = true;
        }
        this.readOnly = readOnly;
        try {
            file = f.open(readOnly ? "r" : "rw");
            if (encryptionTransformer != null) {
                encryptedFile = file;
                file = encryptionTransformer.apply(file);
            }
            try {
                if (readOnly) {
                    fileLock = file.tryLock(0, Long.MAX_VALUE, true);
                } else {
                    fileLock = file.tryLock();
                }
            } catch (OverlappingFileLockException e) {
                throw DataUtils.newMVStoreException(
                        DataUtils.ERROR_FILE_LOCKED,
                        "The file is locked: {0}", fileName, e);
            }
            if (fileLock == null) {
                try { close(); } catch (Exception ignore) {}
                throw DataUtils.newMVStoreException(
                        DataUtils.ERROR_FILE_LOCKED,
                        "The file is locked: {0}", fileName);
            }
            fileSize = file.size();
        } catch (IOException e) {
            try { close(); } catch (Exception ignore) {}
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_READING_FAILED,
                    "Could not open file {0}", fileName, e);
        }
    }

    /**
     * Close this store.
     */
    public void close() {
        try {
            if(file != null && file.isOpen()) {
                if (fileLock != null) {
                    fileLock.release();
                }
                file.close();
            }
        } catch (Exception e) {
            throw DataUtils.newMVStoreException(
                    DataUtils.ERROR_WRITING_FAILED,
                    "Closing failed for file {0}", fileName, e);
        } finally {
            fileLock = null;
            file = null;
        }
    }

    /**
     * Flush all changes.
     */
    public void sync() {
        if (file != null) {
            try {
                file.force(true);
            } catch (IOException e) {
                throw DataUtils.newMVStoreException(
                        DataUtils.ERROR_WRITING_FAILED,
                        "Could not sync file {0}", fileName, e);
            }
        }
    }

    /**
     * Get the file size.
     *
     * @return the file size
     */
    public long size() {
        return fileSize;
    }

    /**
     * Truncate the file.
     *
     * @param size the new file size
     */
    public void truncate(long size) {
        int attemptCount = 0;
        while (true) {
            try {
                writeCount.incrementAndGet();
                file.truncate(size);
                fileSize = Math.min(fileSize, size);
                return;
            } catch (IOException e) {
                if (++attemptCount == 10) {
                    throw DataUtils.newMVStoreException(
                            DataUtils.ERROR_WRITING_FAILED,
                            "Could not truncate file {0} to size {1}",
                            fileName, size, e);
                }
                System.gc();
                Thread.yield();
            }
        }
    }

    /**
     * Get the file instance in use.
     * <p>
     * The application may read from the file (for example for online backup),
     * but not write to it or truncate it.
     *
     * @return the file
     */
    public FileChannel getFile() {
        return file;
    }

    /**
     * Get the encrypted file instance, if encryption is used.
     * <p>
     * The application may read from the file (for example for online backup),
     * but not write to it or truncate it.
     *
     * @return the encrypted file, or null if encryption is not used
     */
    public FileChannel getEncryptedFile() {
        return encryptedFile;
    }

    /**
     * Get the number of write operations since this store was opened.
     * For file based stores, this is the number of file write operations.
     *
     * @return the number of write operations
     */
    public long getWriteCount() {
        return writeCount.get();
    }

    /**
     * Get the number of written bytes since this store was opened.
     *
     * @return the number of write operations
     */
    public long getWriteBytes() {
        return writeBytes.get();
    }

    /**
     * Get the number of read operations since this store was opened.
     * For file based stores, this is the number of file read operations.
     *
     * @return the number of read operations
     */
    public long getReadCount() {
        return readCount.get();
    }

    /**
     * Get the number of read bytes since this store was opened.
     *
     * @return the number of write operations
     */
    public long getReadBytes() {
        return readBytes.get();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Get the default retention time for this store in milliseconds.
     *
     * @return the retention time
     */
    public int getDefaultRetentionTime() {
        return 45_000;
    }

    /**
     * Mark the space as in use.
     *
     * @param pos the position in bytes
     * @param length the number of bytes
     */
    public void markUsed(long pos, int length) {
        freeSpace.markUsed(pos, length);
    }

    /**
     * Allocate a number of blocks and mark them as used.
     *
     * @param length the number of bytes to allocate
     * @param reservedLow start block index of the reserved area (inclusive)
     * @param reservedHigh end block index of the reserved area (exclusive),
     *                     special value -1 means beginning of the infinite free area
     * @return the start position in bytes
     */
    long allocate(int length, long reservedLow, long reservedHigh) {
        return freeSpace.allocate(length, reservedLow, reservedHigh);
    }

    /**
     * Calculate starting position of the prospective allocation.
     *
     * @param blocks the number of blocks to allocate
     * @param reservedLow start block index of the reserved area (inclusive)
     * @param reservedHigh end block index of the reserved area (exclusive),
     *                     special value -1 means beginning of the infinite free area
     * @return the starting block index
     */
    long predictAllocation(int blocks, long reservedLow, long reservedHigh) {
        return freeSpace.predictAllocation(blocks, reservedLow, reservedHigh);
    }

    boolean isFragmented() {
        return freeSpace.isFragmented();
    }

    /**
     * Mark the space as free.
     *
     * @param pos the position in bytes
     * @param length the number of bytes
     */
    public void free(long pos, int length) {
        freeSpace.free(pos, length);
    }

    public int getFillRate() {
        return freeSpace.getFillRate();
    }

    /**
     * Calculates a prospective fill rate, which store would have after rewrite
     * of sparsely populated chunk(s) and evacuation of still live data into a
     * new chunk.
     *
     * @param vacatedBlocks
     *            number of blocks vacated
     * @return prospective fill rate (0 - 100)
     */
    public int getProjectedFillRate(int vacatedBlocks) {
        return freeSpace.getProjectedFillRate(vacatedBlocks);
    }

    long getFirstFree() {
        return freeSpace.getFirstFree();
    }

    long getFileLengthInUse() {
        return freeSpace.getLastFree();
    }

    /**
     * Calculates relative "priority" for chunk to be moved.
     *
     * @param block where chunk starts
     * @return priority, bigger number indicate that chunk need to be moved sooner
     */
    int getMovePriority(int block) {
        return freeSpace.getMovePriority(block);
    }

    long getAfterLastBlock() {
        return freeSpace.getAfterLastBlock();
    }

    /**
     * Mark the file as empty.
     */
    public void clear() {
        freeSpace.clear();
    }

    /**
     * Get the file name.
     *
     * @return the file name
     */
    public String getFileName() {
        return fileName;
    }

}
