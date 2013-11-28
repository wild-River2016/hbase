/*
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.io.hfile.bucket;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.io.hfile.BlockCacheKey;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.CachedBlock;
import org.apache.hadoop.hbase.io.hfile.LruBlockCache;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.HasThread;
import org.apache.hadoop.hbase.util.IdLock;
import org.apache.hadoop.util.StringUtils;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A modified version of BucketCache (by TaoBao/FusionIO) imported from
 * HBASE-7404 patch. Simplified to only handle byte arrays (for use as a
 * L2 cache).
 */
public class BucketCache implements HeapSize {

  private static final Log LOG = LogFactory.getLog(BucketCache.class);

  // TODO: these must be configurable
  /** Priority buckets */
  private static final float DEFAULT_SINGLE_FACTOR = 0.25f;
  private static final float DEFAULT_MULTI_FACTOR = 0.50f;
  private static final float DEFAULT_MEMORY_FACTOR = 0.25f;
  private static final float DEFAULT_EXTRA_FREE_FACTOR = 0.10f;

  private static final float DEFAULT_ACCEPT_FACTOR = 0.95f;
  private static final float DEFAULT_MIN_FACTOR = 0.85f;

  /** Statistics thread */
  private static final int statThreadPeriod = 3 * 60;

  public final static int DEFAULT_WRITER_THREADS = 3;
  public final static int DEFAULT_WRITER_QUEUE_ITEMS = 64;

  // Store/read block data
  private final IOEngine ioEngine;

  // Store the block in this map before writing it to cache
  private final ConcurrentHashMap<BlockCacheKey, RAMQueueEntry> ramCache;
  // In this map, store the block's meta data like offset, length
  private final ConcurrentHashMap<BlockCacheKey, BucketEntry> backingMap;

  /**
   * Flag if the cache is enabled or not... We shut it off if there are IO
   * errors for some time, so that Bucket IO exceptions/errors don't bring down
   * the HBase server.
   */
  private volatile boolean cacheEnabled;

  private final ArrayList<BlockingQueue<RAMQueueEntry>> writerQueues =
      new ArrayList<BlockingQueue<RAMQueueEntry>>();

  private final WriterThread writerThreads[];

  /** Volatile boolean to track if free space is in process or not */
  private volatile boolean freeInProgress = false;
  private final Lock freeSpaceLock = new ReentrantLock();

  private final AtomicLong realCacheSize = new AtomicLong(0);
  private final AtomicLong heapSize = new AtomicLong(0);
  /** Current number of cached elements */
  private final AtomicLong blockNumber = new AtomicLong(0);
  private final AtomicLong failedBlockAdditions = new AtomicLong(0);

  /** Cache access count (sequential ID) */
  private final AtomicLong accessCount = new AtomicLong(0);

  private final Object[] cacheWaitSignals;
  private static final int DEFAULT_CACHE_WAIT_TIME = 50;


  // Used in test now. If the flag is false and the cache speed is very fast,
  // bucket cache will skip some blocks when caching. If the flag is true, we
  // will wait blocks flushed to IOEngine for some time when caching
  boolean wait_when_cache = false;

  private BucketCacheStats cacheStats = new BucketCacheStats();

  /** Approximate block size */
  private final long blockSize;

  /** Duration of IO errors tolerated before we disable cache, 1 min as default */
  private final int ioErrorsTolerationDuration;
  // 1 min
  public static final int DEFAULT_ERROR_TOLERATION_DURATION = 60 * 1000;
  // Start time of first IO error when reading or writing IO Engine, it will be
  // reset after a successful read/write.
  private volatile long ioErrorStartTime = -1;

  /** Minimum buffer size for ByteBufferIOEngine */
  public static final int MIN_BUFFER_SIZE = 4 * 1024 * 1024;

  /**
   * A "sparse lock" implementation allowing to lock on a particular block
   * identified by offset. The purpose of this is to avoid freeing the block
   * which is being read.
   *
   * TODO:We could extend the IdLock to IdReadWriteLock for better.
   */
  private IdLock offsetLock = new IdLock();

  private final ConcurrentIndex<String, BlockCacheKey> blocksByHFile =
      new ConcurrentIndex<String, BlockCacheKey>(new Comparator<BlockCacheKey>() {
        @Override
        public int compare(BlockCacheKey a, BlockCacheKey b) {
          if (a.getOffset() == b.getOffset()) {
            return 0;
          } else if (a.getOffset() < b.getOffset()) {
            return -1;
          }
          return 1;
        }
      });

  /** Statistics thread schedule pool (for heavy debugging, could remove) */
  private final ScheduledExecutorService scheduleThreadPool =
      Executors.newScheduledThreadPool(1,
          new ThreadFactoryBuilder()
              .setNameFormat("BucketCache Statistics #%d")
              .setDaemon(true)
              .build());

  private final int[] bucketSizes;
  // Allocate or free space for the block
  private final BucketAllocator bucketAllocator;

  // TODO (avf): perhaps use a Builder or a separate config object?
  public BucketCache(String ioEngineName, long capacity, int writerThreadNum,
      int writerQLen, int ioErrorsTolerationDuration, int[] bucketSizes,
      Configuration conf)
      throws IOException {
    this.bucketSizes = bucketSizes;
    this.ioEngine = getIOEngineFromName(ioEngineName, capacity, conf);
    this.writerThreads = new WriterThread[writerThreadNum];
    this.cacheWaitSignals = new Object[writerThreadNum];
    long blockNumCapacity = capacity / 16384;
    if (blockNumCapacity >= Integer.MAX_VALUE) {
      // Enough for about 32TB of cache!
      throw new IllegalArgumentException("Cache capacity is too large, only support 32TB now");
    }

    this.blockSize = StoreFile.DEFAULT_BLOCKSIZE_SMALL;
    this.ioErrorsTolerationDuration = ioErrorsTolerationDuration;

    bucketAllocator = new BucketAllocator(bucketSizes, capacity);
    for (int i = 0; i < writerThreads.length; ++i) {
      writerQueues.add(new ArrayBlockingQueue<RAMQueueEntry>(writerQLen));
      this.cacheWaitSignals[i] = new Object();
    }

    this.ramCache = new ConcurrentHashMap<BlockCacheKey, RAMQueueEntry>();

    this.backingMap = new ConcurrentHashMap<BlockCacheKey, BucketEntry>((int) blockNumCapacity);

    final String threadName = Thread.currentThread().getName();
    this.cacheEnabled = true;
    for (int i = 0; i < writerThreads.length; ++i) {
      writerThreads[i] = new WriterThread(writerQueues.get(i), i);
      writerThreads[i].setName(threadName + "-BucketCacheWriter-" + i);
      writerThreads[i].start();
    }
    // Run the statistics thread periodically to print the cache statistics log
    this.scheduleThreadPool.scheduleAtFixedRate(new StatisticsThread(this),
        statThreadPeriod, statThreadPeriod, TimeUnit.SECONDS);
    LOG.info("Started bucket cache");
  }

  /**
   * Get the IOEngine from the IO engine name
   * @param ioEngineName Name of the io engine
   * @param capacity Maximum capacity of the io engine
   * @param conf Optional configuration object for additional parameters
   * @return Instance of the correct IOEngine
   * @throws IllegalArgumentException If the name of the io engine is invalid
   * @throws IOException If there is an error instantiating the io engine
   */
  private IOEngine getIOEngineFromName(String ioEngineName, long capacity,
      Configuration conf)
      throws IOException {
    int requestedBufferSize = conf == null ?
        CacheConfig.DEFAULT_L2_BUCKET_CACHE_BUFFER_SIZE :
        conf.getInt(CacheConfig.L2_BUCKET_CACHE_BUFFER_SIZE_KEY,
            CacheConfig.DEFAULT_L2_BUCKET_CACHE_BUFFER_SIZE);
    int bufferSize = Math.max(requestedBufferSize, bucketSizes[0]);
    boolean isDirect;
    if (ioEngineName.startsWith("offheap"))
      isDirect = true;
    else if (ioEngineName.startsWith("heap"))
      isDirect = false;
    else
      throw new IllegalArgumentException(
          "Don't understand io engine name " + ioEngineName +
              " for cache. Must be heap or offheap");
    LOG.info("Initiating ByteBufferIOEngine with " + ioEngineName +
        " allocation...");
    if (bufferSize != requestedBufferSize) {
      LOG.warn("Requested per-buffer size " + requestedBufferSize +
          ", but actual per-buffer size will be: " + bufferSize);
    } else {
      LOG.info("Size per-buffer: " + StringUtils.humanReadableInt(bufferSize));
    }
    return new ByteBufferIOEngine(capacity, bufferSize, isDirect);
  }

  /**
   * Cache the block with the specified name and buffer.
   * @param cacheKey block's cache key
   * @param buf block buffer
   */
  public void cacheBlock(BlockCacheKey cacheKey, byte[] buf) {
    cacheBlock(cacheKey, buf, false);
  }

  /**
   * Cache the block with the specified name and buffer.
   * @param cacheKey block's cache key
   * @param cachedItem block buffer
   * @param inMemory if block is in-memory
   */
  public void cacheBlock(BlockCacheKey cacheKey, byte[] cachedItem, boolean inMemory) {
    cacheBlockWithWait(cacheKey, cachedItem, inMemory, wait_when_cache);
  }

  /**
   * Cache the block to ramCache
   * @param cacheKey block's cache key
   * @param cachedItem block buffer
   * @param inMemory if block is in-memory
   * @param wait if true, blocking wait when queue is full
   */
  public void cacheBlockWithWait(BlockCacheKey cacheKey, byte[] cachedItem,
      boolean inMemory, boolean wait) {
    if (!cacheEnabled)
      return;

    if (backingMap.containsKey(cacheKey) || ramCache.containsKey(cacheKey))
      return;

    /*
     * Stuff the entry into the RAM cache so it can get drained to the
     * persistent store
     */
    RAMQueueEntry re = new RAMQueueEntry(cacheKey, cachedItem,
        accessCount.incrementAndGet(), inMemory);
    ramCache.put(cacheKey, re);
    int queueNum = (cacheKey.hashCode() & 0x7FFFFFFF) % writerQueues.size();
    BlockingQueue<RAMQueueEntry> bq = writerQueues.get(queueNum);
    boolean successfulAddition = bq.offer(re);
    if (!successfulAddition && wait) {
      synchronized (cacheWaitSignals[queueNum]) {
        try {
          cacheWaitSignals[queueNum].wait(DEFAULT_CACHE_WAIT_TIME);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
      successfulAddition = bq.offer(re);
    }
    if (!successfulAddition) {
      ramCache.remove(cacheKey);
      failedBlockAdditions.incrementAndGet();
    } else {
      this.blockNumber.incrementAndGet();
      this.heapSize.addAndGet(cachedItem.length);
      blocksByHFile.put(cacheKey.getHfileName(), cacheKey);
    }
  }


  /**
   * Get the buffer of the block with the specified key.
   * @param key block's cache key
   * @param caching true if the caller caches blocks on cache misses
   */
  public byte[] getBlock(BlockCacheKey key, boolean caching) {
    return getBlock(key, caching, false);
  }

  /**
   * Get the buffer of the block with the specified key.
   * @param key block's cache key
   * @param caching true if the caller caches blocks on cache misses
   * @param repeat Whether this is a repeat lookup for the same block
   * @return buffer of specified cache key, or null if not in cache
   */
  public byte[] getBlock(BlockCacheKey key, boolean caching, boolean repeat) {
    if (!cacheEnabled)
      return null;
    RAMQueueEntry re = ramCache.get(key);
    if (re != null) {
      cacheStats.hit(caching);
      re.access(accessCount.incrementAndGet());
      return re.getData();
    }
    BucketEntry bucketEntry = backingMap.get(key);
    if(bucketEntry!=null) {
      long start = System.nanoTime();
      IdLock.Entry lockEntry = null;
      try {
        lockEntry = offsetLock.getLockEntry(bucketEntry.offset());
        if (bucketEntry.equals(backingMap.get(key))) {
          int len = bucketEntry.getLength();
          byte[] bytes = new byte[len];
          ioEngine.read(bytes, bucketEntry.offset());
          long timeTaken = System.nanoTime() - start;
          cacheStats.hit(caching);
          cacheStats.ioHit(timeTaken);
          bucketEntry.access(accessCount.incrementAndGet());
          if (this.ioErrorStartTime > 0) {
            ioErrorStartTime = -1;
          }
          return bytes;
        }
      } catch (IOException ioex) {
        LOG.error("Failed reading block " + key + " from bucket cache", ioex);
        checkIOErrorIsTolerated();
      } finally {
        if (lockEntry != null) {
          offsetLock.releaseLockEntry(lockEntry);
        }
      }
    }
    if(!repeat)cacheStats.miss(caching);
    return null;
  }

  public void clearCache() {
    for (BlockCacheKey key: this.backingMap.keySet()) {
      evictBlock(key);
    }
  }

  public boolean evictBlock(BlockCacheKey cacheKey) {
    if (!cacheEnabled) return false;
    RAMQueueEntry removedBlock = ramCache.remove(cacheKey);
    if (removedBlock != null) {
      this.blockNumber.decrementAndGet();
      this.heapSize.addAndGet(-1 * removedBlock.getData().length);
    }
    BucketEntry bucketEntry = backingMap.get(cacheKey);
    if (bucketEntry == null) { return false; }
    IdLock.Entry lockEntry = null;
    try {
      lockEntry = offsetLock.getLockEntry(bucketEntry.offset());
      if (bucketEntry.equals(backingMap.remove(cacheKey))) {
        bucketAllocator.freeBlock(bucketEntry.offset());
        realCacheSize.addAndGet(-1 * bucketEntry.getLength());
        blocksByHFile.remove(cacheKey.getHfileName(), cacheKey);
        if (removedBlock == null) {
          this.blockNumber.decrementAndGet();
        }
      } else {
        return false;
      }
    } catch (IOException ie) {
      LOG.warn("Failed evicting block " + cacheKey);
      return false;
    } finally {
      if (lockEntry != null) {
        offsetLock.releaseLockEntry(lockEntry);
      }
    }
    cacheStats.evicted(bucketEntry.getPriority());
    return true;
  }

  /*
   * Statistics thread.  Periodically prints the cache statistics to the log.
   */
  private static class StatisticsThread extends Thread {
    BucketCache bucketCache;

    public StatisticsThread(BucketCache bucketCache) {
      super("BucketCache.StatisticsThread");
      setDaemon(true);
      this.bucketCache = bucketCache;
    }
    @Override
    public void run() {
      bucketCache.logStats();
    }
  }

  public void logStats() {
    if (!LOG.isDebugEnabled()) return;
    // Log size
    long totalSize = bucketAllocator.getTotalSize();
    long usedSize = bucketAllocator.getUsedSize();
    long freeSize = totalSize - usedSize;
    long cacheSize = this.realCacheSize.get();
    LOG.debug("BucketCache Stats: " +
        "failedBlockAdditions=" + this.failedBlockAdditions.get() + ", " +
        "total=" + StringUtils.byteDesc(totalSize) + ", " +
        "free=" + StringUtils.byteDesc(freeSize) + ", " +
        "usedSize=" + StringUtils.byteDesc(usedSize) +", " +
        "cacheSize=" + StringUtils.byteDesc(cacheSize) +", " +
        "accesses=" + cacheStats.getRequestCount() + ", " +
        "hits=" + cacheStats.getHitCount() + ", " +
        "IOhitsPerSecond=" + cacheStats.getIOHitsPerSecond() + ", " +
        "IOTimePerHit=" + String.format("%.2f", cacheStats.getIOTimePerHit())+ ", " +
        "hitRatio=" + (cacheStats.getHitCount() == 0 ? "0," :
                           (StringUtils.formatPercent(cacheStats.getHitRatio(), 2)+ ", ")) +
        "cachingAccesses=" + cacheStats.getRequestCachingCount() + ", " +
        "cachingHits=" + cacheStats.getHitCachingCount() + ", " +
        "cachingHitsRatio=" +(cacheStats.getHitCachingCount() == 0 ? "0," :
                                  (StringUtils.formatPercent(cacheStats.getHitCachingRatio(), 2)+ ", ")) +
        "evictions=" + cacheStats.getEvictionCount() + ", " +
        "evicted=" + cacheStats.getEvictedCount() + ", " +
        "evictedPerRun=" + cacheStats.evictedPerEviction());
    cacheStats.reset();
  }

  private long acceptableSize() {
    return (long) Math.floor(bucketAllocator.getTotalSize() * DEFAULT_ACCEPT_FACTOR);
  }

  private long minSize() {
    return (long) Math.floor(bucketAllocator.getTotalSize() * DEFAULT_MIN_FACTOR);
  }

  private long singleSize() {
    return (long) Math.floor(bucketAllocator.getTotalSize()
        * DEFAULT_SINGLE_FACTOR * DEFAULT_MIN_FACTOR);
  }

  private long multiSize() {
    return (long) Math.floor(bucketAllocator.getTotalSize() * DEFAULT_MULTI_FACTOR
        * DEFAULT_MIN_FACTOR);
  }

  private long memorySize() {
    return (long) Math.floor(bucketAllocator.getTotalSize() * DEFAULT_MEMORY_FACTOR
        * DEFAULT_MIN_FACTOR);
  }

  /**
   * Free the space if the used size reaches acceptableSize() or one size block
   * couldn't be allocated. When freeing the space, we use the LRU algorithm and
   * ensure there must be some blocks evicted
   */
  private void freeSpace() {
    // Ensure only one freeSpace progress at a time
    if (!freeSpaceLock.tryLock()) return;
    try {
      freeInProgress = true;
      long bytesToFreeWithoutExtra = 0;
      /*
       * Calculate free byte for each bucketSizeinfo
       */
      StringBuffer msgBuffer = new StringBuffer();
      BucketAllocator.IndexStatistics[] stats = bucketAllocator.getIndexStatistics();
      long[] bytesToFreeForBucket = new long[stats.length];
      for (int i = 0; i < stats.length; i++) {
        bytesToFreeForBucket[i] = 0;
        long freeGoal = (long) Math.floor(stats[i].totalCount()
            * (1 - DEFAULT_MIN_FACTOR));
        freeGoal = Math.max(freeGoal, 1);
        if (stats[i].freeCount() < freeGoal) {
          bytesToFreeForBucket[i] = stats[i].itemSize()
              * (freeGoal - stats[i].freeCount());
          bytesToFreeWithoutExtra += bytesToFreeForBucket[i];
          msgBuffer.append("Free for bucketSize(" + stats[i].itemSize() + ")="
              + StringUtils.byteDesc(bytesToFreeForBucket[i]) + ", ");
        }
      }
      msgBuffer.append("Free for total="
          + StringUtils.byteDesc(bytesToFreeWithoutExtra) + ", ");

      if (bytesToFreeWithoutExtra <= 0) {
        return;
      }
      long currentSize = bucketAllocator.getUsedSize();
      long totalSize=bucketAllocator.getTotalSize();
      LOG.debug("Bucket cache free space started; Attempting to  " + msgBuffer.toString()
          + " of current used=" + StringUtils.byteDesc(currentSize)
          + ",actual cacheSize=" + StringUtils.byteDesc(realCacheSize.get())
          + ",total=" + StringUtils.byteDesc(totalSize));

      long bytesToFreeWithExtra = (long) Math.floor(bytesToFreeWithoutExtra
          * (1 + DEFAULT_EXTRA_FREE_FACTOR));

      // Instantiate priority buckets
      BucketEntryGroup bucketSingle = new BucketEntryGroup(bytesToFreeWithExtra,
          blockSize, singleSize());
      BucketEntryGroup bucketMulti = new BucketEntryGroup(bytesToFreeWithExtra,
          blockSize, multiSize());
      BucketEntryGroup bucketMemory = new BucketEntryGroup(bytesToFreeWithExtra,
          blockSize, memorySize());

      // Scan entire map putting bucket entry into appropriate bucket entry
      // group
      for (Map.Entry<BlockCacheKey, BucketEntry> bucketEntryWithKey : backingMap.entrySet()) {
        switch (bucketEntryWithKey.getValue().getPriority()) {
          case SINGLE: {
            bucketSingle.add(bucketEntryWithKey);
            break;
          }
          case MULTI: {
            bucketMulti.add(bucketEntryWithKey);
            break;
          }
          case MEMORY: {
            bucketMemory.add(bucketEntryWithKey);
            break;
          }
        }
      }

      PriorityQueue<BucketEntryGroup> bucketQueue = new PriorityQueue<BucketEntryGroup>(3);

      bucketQueue.add(bucketSingle);
      bucketQueue.add(bucketMulti);
      bucketQueue.add(bucketMemory);

      int remainingBuckets = 3;
      long bytesFreed = 0;

      BucketEntryGroup bucketGroup;
      while ((bucketGroup = bucketQueue.poll()) != null) {
        long overflow = bucketGroup.overflow();
        if (overflow > 0) {
          long bucketBytesToFree = Math.min(overflow,
              (bytesToFreeWithoutExtra - bytesFreed) / remainingBuckets);
          bytesFreed += bucketGroup.free(bucketBytesToFree);
        }
        remainingBuckets--;
      }

      /**
       * Check whether need extra free because some bucketSizeinfo still needs
       * free space
       */
      stats = bucketAllocator.getIndexStatistics();
      boolean needFreeForExtra = false;
      for (int i = 0; i < stats.length; i++) {
        long freeGoal = (long) Math.floor(stats[i].totalCount()
            * (1 - DEFAULT_MIN_FACTOR));
        freeGoal = Math.max(freeGoal, 1);
        if (stats[i].freeCount() < freeGoal) {
          needFreeForExtra = true;
          break;
        }
      }

      if (needFreeForExtra) {
        bucketQueue.clear();
        remainingBuckets = 2;

        bucketQueue.add(bucketSingle);
        bucketQueue.add(bucketMulti);

        while ((bucketGroup = bucketQueue.poll()) != null) {
          long bucketBytesToFree = (bytesToFreeWithExtra - bytesFreed)
              / remainingBuckets;
          bytesFreed += bucketGroup.free(bucketBytesToFree);
          remainingBuckets--;
        }
      }

      if (LOG.isDebugEnabled()) {
        long single = bucketSingle.totalSize();
        long multi = bucketMulti.totalSize();
        long memory = bucketMemory.totalSize();
        LOG.debug("Bucket cache free space completed; " + "freed="
            + StringUtils.byteDesc(bytesFreed) + ", " + "total="
            + StringUtils.byteDesc(totalSize) + ", " + "single="
            + StringUtils.byteDesc(single) + ", " + "multi="
            + StringUtils.byteDesc(multi) + ", " + "memory="
            + StringUtils.byteDesc(memory));
      }

    } finally {
      cacheStats.evict();
      freeInProgress = false;
      freeSpaceLock.unlock();
    }
  }

  // This handles flushing the RAM cache to IOEngine.
  private class WriterThread extends HasThread {
    BlockingQueue<RAMQueueEntry> inputQueue;
    final int threadIdx;
    boolean writerEnabled = true;

    WriterThread(BlockingQueue<RAMQueueEntry> queue, int threadNO) {
      super();
      this.inputQueue = queue;
      this.threadIdx = threadNO;
      setDaemon(true);
    }

    // Used for test
    void disableWriter() {
      this.writerEnabled = false;
    }

    public void run() {
      List<RAMQueueEntry> entries = new ArrayList<RAMQueueEntry>();
      try {
        while (cacheEnabled && writerEnabled) {
          try {
            // Perform a block take first in order to avoid a drainTo()
            // on an empty queue looping around and causing starvation.
            entries.add(inputQueue.take());
            inputQueue.drainTo(entries);
            synchronized (cacheWaitSignals[threadIdx]) {
              cacheWaitSignals[threadIdx].notifyAll();
            }
          } catch (InterruptedException ie) {
            if (!cacheEnabled) break;
          }
          doDrain(entries);
        }
      } catch (Throwable t) {
        LOG.warn("Failed doing drain", t);
      }
      LOG.info(this.getName() + " exiting, cacheEnabled=" + cacheEnabled);
    }

    /**
     * Flush the entries in ramCache to IOEngine and add bucket entry to
     * backingMap
     * @param entries
     * @throws InterruptedException
     */
    private void doDrain(List<RAMQueueEntry> entries)
        throws InterruptedException {
      BucketEntry[] bucketEntries = new BucketEntry[entries.size()];
      RAMQueueEntry[] ramEntries = new RAMQueueEntry[entries.size()];
      int done = 0;
      while (entries.size() > 0 && cacheEnabled) {
        // Keep going in case we throw...
        RAMQueueEntry ramEntry = null;
        try {
          ramEntry = entries.remove(entries.size() - 1);
          if (ramEntry == null) {
            LOG.warn("Couldn't get the entry from RAM queue, who steals it?");
            continue;
          }
          BucketEntry bucketEntry = ramEntry.writeToCache(ioEngine,
              bucketAllocator, realCacheSize);
          ramEntries[done] = ramEntry;
          bucketEntries[done++] = bucketEntry;
          if (ioErrorStartTime > 0) {
            ioErrorStartTime = -1;
          }
        } catch (BucketAllocatorException fle) {
          LOG.warn("Failed allocating for block "
              + (ramEntry == null ? "" : ramEntry.getKey()), fle);
        } catch (CacheFullException cfe) {
          if (!freeInProgress) {
            freeSpace();
          } else {
            Thread.sleep(50);
          }
        } catch (IOException ioex) {
          LOG.error("Failed writing to bucket cache", ioex);
          checkIOErrorIsTolerated();
        }
      }

      // Make sure that the data pages we have written are on the media before
      // we update the map.
      try {
        ioEngine.sync();
      } catch (IOException ioex) {
        LOG.error("Faild syncing IO engine", ioex);
        checkIOErrorIsTolerated();
        // Since we failed sync, free the blocks in bucket allocator
        for (int i = 0; i < done; ++i) {
          if (bucketEntries[i] != null) {
            bucketAllocator.freeBlock(bucketEntries[i].offset());
          }
        }
        done = 0;
      }

      for (int i = 0; i < done; ++i) {
        if (bucketEntries[i] != null) {
          backingMap.put(ramEntries[i].getKey(), bucketEntries[i]);
        }
        RAMQueueEntry ramCacheEntry = ramCache.remove(ramEntries[i].getKey());
        if (ramCacheEntry != null) {
          heapSize.addAndGet(-1 * ramEntries[i].getData().length);
        }
      }

      if (bucketAllocator.getUsedSize() > acceptableSize()) {
        freeSpace();
      }
    }
  }

  /**
   * Check whether we tolerate IO error this time. If the duration of IOEngine
   * throwing errors exceeds ioErrorsDurationTimeTolerated, we will disable the
   * cache
   */
  private void checkIOErrorIsTolerated() {
    long now = EnvironmentEdgeManager.currentTimeMillis();
    if (this.ioErrorStartTime > 0) {
      if (cacheEnabled
          && (now - ioErrorStartTime) > this.ioErrorsTolerationDuration) {
        LOG.error("IO errors duration time has exceeded "
            + ioErrorsTolerationDuration
            + "ms, disabing cache, please check your IOEngine");
        disableCache();
      }
    } else {
      this.ioErrorStartTime = now;
    }
  }

  /**
   * Used to shut down the cache -or- turn it off in the case of something
   * broken.
   */
  private void disableCache() {
    if (!cacheEnabled)
      return;
    cacheEnabled = false;
    ioEngine.shutdown();
    this.scheduleThreadPool.shutdown();
    for (int i = 0; i < writerThreads.length; ++i)
      writerThreads[i].interrupt();
    this.ramCache.clear();
    this.backingMap.clear();
    this.cacheStats.reset();
  }

  private void join() throws InterruptedException {
    for (int i = 0; i < writerThreads.length; ++i)
      writerThreads[i].join();
  }

  public void shutdown() {
    disableCache();
    LOG.info("Shutting down bucket cache");
  }

  public LruBlockCache.CacheStats getStats() {
    return cacheStats;
  }

  BucketAllocator getAllocator() {
    return this.bucketAllocator;
  }

  public long heapSize() {
    return this.heapSize.get();
  }

  public boolean isEnabled() {
    return cacheEnabled;
  }

  /**
   * Returns the total size of the block cache, in bytes.
   * @return size of cache, in bytes
   */
  public long size() {
    return this.realCacheSize.get();
  }

  public long getFreeSize() {
    return this.bucketAllocator.getTotalSize() - this.bucketAllocator.getUsedSize();
  }

  public long getBlockCount() {
    return this.blockNumber.get();
  }

  public long getEvictedCount() {
    return cacheStats.getEvictedCount();
  }

  /**
   * Evicts all blocks for a specific HFile. This is an expensive operation
   * implemented as a linear-time search through all blocks in the cache.
   * Ideally this should be a search in a log-access-time map.
   *
   * <p>
   * This is used for evict-on-close to remove all blocks of a specific HFile.
   *
   * @return the number of blocks evicted
   */
  public int evictBlocksByHfileName(String hfileName) {
    // Copy the list to avoid ConcurrentModificationException
    // as evictBlockKey removes the key from the index
    Set<BlockCacheKey> keySet = blocksByHFile.values(hfileName);
    if (keySet == null) {
      return 0;
    }
    int numEvicted = 0;
    List<BlockCacheKey> keysForHFile =
        ImmutableList.copyOf(keySet);
    for (BlockCacheKey key : keysForHFile) {
      if (evictBlock(key)) {
        ++numEvicted;
      }
    }
    return numEvicted;
  }


  /**
   * Item in cache. We expect this to be where most memory goes. Java uses 8
   * bytes just for object headers; after this, we want to use as little as
   * possible - so we only use 8 bytes, but in order to do so we end up messing
   * around with all this Java casting stuff. Offset stored as 5 bytes that make
   * up the long. Doubt we'll see devices this big for ages. Offsets are divided
   * by 256. So 5 bytes gives us 256TB or so.
   */
  static class BucketEntry implements Serializable, Comparable<BucketEntry> {
    private static final long serialVersionUID = -6741504807982257534L;
    private int offsetBase;
    private int length;
    private byte offset1;
    private volatile long accessTime;
    private CachedBlock.BlockPriority priority;

    BucketEntry(long offset, int length, long accessTime, boolean inMemory) {
      setOffset(offset);
      this.length = length;
      this.accessTime = accessTime;
      if (inMemory) {
        this.priority = CachedBlock.BlockPriority.MEMORY;
      } else {
        this.priority = CachedBlock.BlockPriority.SINGLE;
      }
    }

    long offset() { // Java has no unsigned numbers
      long o = ((long) offsetBase) & 0xFFFFFFFF;
      o += (((long) (offset1)) & 0xFF) << 32;
      return o << 8;
    }

    private void setOffset(long value) {
      Preconditions.checkArgument((value & 0xFF) == 0);
      value >>= 8;
      offsetBase = (int) value;
      offset1 = (byte) (value >> 32);
    }

    public int getLength() {
      return length;
    }

    /**
     * Block has been accessed. Update its local access time.
     */
    public void access(long accessTime) {
      this.accessTime = accessTime;
      if (this.priority == CachedBlock.BlockPriority.SINGLE) {
        this.priority = CachedBlock.BlockPriority.MULTI;
      }
    }

    public CachedBlock.BlockPriority getPriority() {
      return this.priority;
    }

    @Override
    public int compareTo(BucketEntry that) {
      if(this.accessTime == that.accessTime) return 0;
      return this.accessTime < that.accessTime ? 1 : -1;
    }

    @Override
    public boolean equals(Object that) {
      return this == that;
    }
  }

  /**
   * Used to group bucket entries into priority buckets. There will be a
   * BucketEntryGroup for each priority (single, multi, memory). Once bucketed,
   * the eviction algorithm takes the appropriate number of elements out of each
   * according to configuration parameters and their relative sizes.
   */
  private class BucketEntryGroup implements Comparable<BucketEntryGroup> {

    private CachedEntryQueue queue;
    private long totalSize = 0;
    private long bucketSize;

    public BucketEntryGroup(long bytesToFree, long blockSize, long bucketSize) {
      this.bucketSize = bucketSize;
      queue = new CachedEntryQueue(bytesToFree, blockSize);
      totalSize = 0;
    }

    public void add(Map.Entry<BlockCacheKey, BucketEntry> block) {
      totalSize += block.getValue().getLength();
      queue.add(block);
    }

    /**
     * Free specified number of bytes by freeing the least recently used
     * buckets entries.
     * @param toFree Number of bytes we want to free
     * @return Number of bytes freed
     */
    public long free(long toFree) {
      Map.Entry<BlockCacheKey, BucketEntry> entry;
      long freedBytes = 0;
      while ((entry = queue.pollLast()) != null) {
        evictBlock(entry.getKey());
        freedBytes += entry.getValue().getLength();
        if (freedBytes >= toFree) {
          return freedBytes;
        }
      }
      return freedBytes;
    }

    public long overflow() {
      return totalSize - bucketSize;
    }

    public long totalSize() {
      return totalSize;
    }

    @Override
    public int compareTo(BucketEntryGroup that) {
      if (this.overflow() == that.overflow())
        return 0;
      return this.overflow() > that.overflow() ? 1 : -1;
    }

    @Override
    public boolean equals(Object that) {
      return this == that;
    }

  }

  /**
   * Only used in test
   * @throws InterruptedException
   */
  void stopWriterThreads() throws InterruptedException {
    for (WriterThread writerThread : writerThreads) {
      writerThread.disableWriter();
      writerThread.interrupt();
      writerThread.join();
    }
  }
}
