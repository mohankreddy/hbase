/*
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
package org.apache.hadoop.hbase.regionserver;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.protobuf.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.DroppedSnapshotException;
import org.apache.hadoop.hbase.FailedSanityCheckException;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HConstants.OperationStatusCode;
import org.apache.hadoop.hbase.HDFSBlocksDistribution;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.UnknownScannerException;
import org.apache.hadoop.hbase.backup.HFileArchiver;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.IsolationLevel;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.RowLock;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.coprocessor.Exec;
import org.apache.hadoop.hbase.client.coprocessor.ExecResult;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterWrapper;
import org.apache.hadoop.hbase.filter.IncompatibleFilterException;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.ipc.CoprocessorProtocol;
import org.apache.hadoop.hbase.ipc.HBaseRPC;
import org.apache.hadoop.hbase.ipc.HBaseServer;
import org.apache.hadoop.hbase.ipc.RpcCallContext;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.regionserver.MultiVersionConsistencyControl.WriteEntry;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.HLogFactory;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.HLogUtil;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CancelableProgressable;
import org.apache.hadoop.hbase.util.ClassSize;
import org.apache.hadoop.hbase.util.CompressionTest;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.HashedBytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.io.MultipleIOException;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.StringUtils;
import org.cliffc.high_scale_lib.Counter;

import com.google.common.base.Preconditions;
import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MutableClassToInstanceMap;

import static org.apache.hadoop.hbase.protobuf.generated.ClientProtos.CoprocessorServiceCall;

/**
 * HRegion stores data for a certain region of a table.  It stores all columns
 * for each row. A given table consists of one or more HRegions.
 *
 * <p>We maintain multiple HStores for a single HRegion.
 *
 * <p>An Store is a set of rows with some column data; together,
 * they make up all the data for the rows.
 *
 * <p>Each HRegion has a 'startKey' and 'endKey'.
 * <p>The first is inclusive, the second is exclusive (except for
 * the final region)  The endKey of region 0 is the same as
 * startKey for region 1 (if it exists).  The startKey for the
 * first region is null. The endKey for the final region is null.
 *
 * <p>Locking at the HRegion level serves only one purpose: preventing the
 * region from being closed (and consequently split) while other operations
 * are ongoing. Each row level operation obtains both a row lock and a region
 * read lock for the duration of the operation. While a scanner is being
 * constructed, getScanner holds a read lock. If the scanner is successfully
 * constructed, it holds a read lock until it is closed. A close takes out a
 * write lock and consequently will block for ongoing operations and will block
 * new operations from starting while the close is in progress.
 *
 * <p>An HRegion is defined by its table and its key extent.
 *
 * <p>It consists of at least one Store.  The number of Stores should be
 * configurable, so that data which is accessed together is stored in the same
 * Store.  Right now, we approximate that by building a single Store for
 * each column family.  (This config info will be communicated via the
 * tabledesc.)
 *
 * <p>The HTableDescriptor contains metainfo about the HRegion's table.
 * regionName is a unique identifier for this HRegion. (startKey, endKey]
 * defines the keyspace for this HRegion.
 */
@InterfaceAudience.Private
public class HRegion implements HeapSize { // , Writable{
  public static final Log LOG = LogFactory.getLog(HRegion.class);
  private static final String MERGEDIR = ".merges";

  final AtomicBoolean closed = new AtomicBoolean(false);
  /* Closing can take some time; use the closing flag if there is stuff we don't
   * want to do while in closing state; e.g. like offer this region up to the
   * master as a region to close if the carrying regionserver is overloaded.
   * Once set, it is never cleared.
   */
  final AtomicBoolean closing = new AtomicBoolean(false);

  protected long completeSequenceId = -1L;

  //////////////////////////////////////////////////////////////////////////////
  // Members
  //////////////////////////////////////////////////////////////////////////////

  private final ConcurrentHashMap<HashedBytes, CountDownLatch> lockedRows =
    new ConcurrentHashMap<HashedBytes, CountDownLatch>();
  private final ConcurrentHashMap<Integer, HashedBytes> lockIds =
    new ConcurrentHashMap<Integer, HashedBytes>();
  private final AtomicInteger lockIdGenerator = new AtomicInteger(1);
  static private Random rand = new Random();

  protected final Map<byte[], Store> stores = new ConcurrentSkipListMap<byte[], Store>(
      Bytes.BYTES_RAWCOMPARATOR);

  // Registered region protocol handlers
  private ClassToInstanceMap<CoprocessorProtocol>
      protocolHandlers = MutableClassToInstanceMap.create();

  private Map<String, Class<? extends CoprocessorProtocol>>
      protocolHandlerNames = Maps.newHashMap();

  // TODO: account for each registered handler in HeapSize computation
  private Map<String, Service> coprocessorServiceHandlers = Maps.newHashMap();

  /**
   * Temporary subdirectory of the region directory used for compaction output.
   */
  public static final String REGION_TEMP_SUBDIR = ".tmp";

  //These variable are just used for getting data out of the region, to test on
  //client side
  // private int numStores = 0;
  // private int [] storeSize = null;
  // private byte [] name = null;

  public final AtomicLong memstoreSize = new AtomicLong(0);

  // Debug possible data loss due to WAL off
  final Counter numPutsWithoutWAL = new Counter();
  final Counter dataInMemoryWithoutWAL = new Counter();

  // Debug why CAS operations are taking a while.
  final Counter checkAndMutateChecksPassed = new Counter();
  final Counter checkAndMutateChecksFailed = new Counter();

  //Number of requests
  final Counter readRequestsCount = new Counter();
  final Counter writeRequestsCount = new Counter();

  //How long operations were blocked by a memstore over highwater.
  final Counter updatesBlockedMs = new Counter();

  /**
   * The directory for the table this region is part of.
   * This directory contains the directory for this region.
   */
  final Path tableDir;

  final HLog log;
  final FileSystem fs;
  final Configuration conf;
  final Configuration baseConf;
  final int rowLockWaitDuration;
  static final int DEFAULT_ROWLOCK_WAIT_DURATION = 30000;

  // negative number indicates infinite timeout
  static final long DEFAULT_ROW_PROCESSOR_TIMEOUT = 60 * 1000L;
  final ExecutorService rowProcessorExecutor = Executors.newCachedThreadPool();

  final HRegionInfo regionInfo;
  final Path regiondir;
  KeyValue.KVComparator comparator;

  private ConcurrentHashMap<RegionScanner, Long> scannerReadPoints;

  /**
   * @return The smallest mvcc readPoint across all the scanners in this
   * region. Writes older than this readPoint, are included  in every
   * read operation.
   */
  public long getSmallestReadPoint() {
    long minimumReadPoint;
    // We need to ensure that while we are calculating the smallestReadPoint
    // no new RegionScanners can grab a readPoint that we are unaware of.
    // We achieve this by synchronizing on the scannerReadPoints object.
    synchronized(scannerReadPoints) {
      minimumReadPoint = mvcc.memstoreReadPoint();

      for (Long readPoint: this.scannerReadPoints.values()) {
        if (readPoint < minimumReadPoint) {
          minimumReadPoint = readPoint;
        }
      }
    }
    return minimumReadPoint;
  }
  /*
   * Data structure of write state flags used coordinating flushes,
   * compactions and closes.
   */
  static class WriteState {
    // Set while a memstore flush is happening.
    volatile boolean flushing = false;
    // Set when a flush has been requested.
    volatile boolean flushRequested = false;
    // Number of compactions running.
    volatile int compacting = 0;
    // Gets set in close. If set, cannot compact or flush again.
    volatile boolean writesEnabled = true;
    // Set if region is read-only
    volatile boolean readOnly = false;

    /**
     * Set flags that make this region read-only.
     *
     * @param onOff flip value for region r/o setting
     */
    synchronized void setReadOnly(final boolean onOff) {
      this.writesEnabled = !onOff;
      this.readOnly = onOff;
    }

    boolean isReadOnly() {
      return this.readOnly;
    }

    boolean isFlushRequested() {
      return this.flushRequested;
    }

    static final long HEAP_SIZE = ClassSize.align(
        ClassSize.OBJECT + 5 * Bytes.SIZEOF_BOOLEAN);
  }

  final WriteState writestate = new WriteState();

  long memstoreFlushSize;
  final long timestampSlop;
  final long rowProcessorTimeout;
  private volatile long lastFlushTime;
  final RegionServerServices rsServices;
  private RegionServerAccounting rsAccounting;
  private List<Pair<Long, Long>> recentFlushes = new ArrayList<Pair<Long,Long>>();
  private long blockingMemStoreSize;
  final long threadWakeFrequency;
  // Used to guard closes
  final ReentrantReadWriteLock lock =
    new ReentrantReadWriteLock();

  // Stop updates lock
  private final ReentrantReadWriteLock updatesLock =
    new ReentrantReadWriteLock();
  private boolean splitRequest;
  private byte[] explicitSplitPoint = null;

  private final MultiVersionConsistencyControl mvcc =
      new MultiVersionConsistencyControl();

  // Coprocessor host
  private RegionCoprocessorHost coprocessorHost;

  /**
   * Name of the region info file that resides just under the region directory.
   */
  public final static String REGIONINFO_FILE = ".regioninfo";
  private HTableDescriptor htableDescriptor = null;
  private RegionSplitPolicy splitPolicy;

  private final MetricsRegion metricsRegion;

  /**
   * Should only be used for testing purposes
   */
  public HRegion(){
    this.tableDir = null;
    this.blockingMemStoreSize = 0L;
    this.conf = null;
    this.rowLockWaitDuration = DEFAULT_ROWLOCK_WAIT_DURATION;
    this.rsServices = null;
    this.baseConf = null;
    this.fs = null;
    this.timestampSlop = HConstants.LATEST_TIMESTAMP;
    this.rowProcessorTimeout = DEFAULT_ROW_PROCESSOR_TIMEOUT;
    this.memstoreFlushSize = 0L;
    this.log = null;
    this.regiondir = null;
    this.regionInfo = null;
    this.htableDescriptor = null;
    this.threadWakeFrequency = 0L;
    this.coprocessorHost = null;
    this.scannerReadPoints = new ConcurrentHashMap<RegionScanner, Long>();

    this.metricsRegion = new MetricsRegion(new MetricsRegionWrapperImpl(this));
  }

  /**
   * HRegion copy constructor. Useful when reopening a closed region (normally
   * for unit tests)
   * @param other original object
   */
  public HRegion(HRegion other) {
    this(other.getTableDir(), other.getLog(), other.getFilesystem(),
        other.baseConf, other.getRegionInfo(), other.getTableDesc(), null);
  }

  /**
   * HRegion constructor.  his constructor should only be used for testing and
   * extensions.  Instances of HRegion should be instantiated with the
   * {@link HRegion#newHRegion(Path, HLog, FileSystem, Configuration, HRegionInfo, HTableDescriptor, RegionServerServices)} method.
   *
   *
   * @param tableDir qualified path of directory where region should be located,
   * usually the table directory.
   * @param log The HLog is the outbound log for any updates to the HRegion
   * (There's a single HLog for all the HRegions on a single HRegionServer.)
   * The log file is a logfile from the previous execution that's
   * custom-computed for this HRegion. The HRegionServer computes and sorts the
   * appropriate log info for this HRegion. If there is a previous log file
   * (implying that the HRegion has been written-to before), then read it from
   * the supplied path.
   * @param fs is the filesystem.
   * @param conf is global configuration settings.
   * @param regionInfo - HRegionInfo that describes the region
   * is new), then read them from the supplied path.
   * @param rsServices reference to {@link RegionServerServices} or null
   *
   * @see HRegion#newHRegion(Path, HLog, FileSystem, Configuration, HRegionInfo, HTableDescriptor, RegionServerServices)
   */
  public HRegion(Path tableDir, HLog log, FileSystem fs,
      Configuration confParam, final HRegionInfo regionInfo,
      final HTableDescriptor htd, RegionServerServices rsServices) {
    this.tableDir = tableDir;
    this.comparator = regionInfo.getComparator();
    this.log = log;
    this.fs = fs;
    if (confParam instanceof CompoundConfiguration) {
      throw new IllegalArgumentException("Need original base configuration");
    }
    // 'conf' renamed to 'confParam' b/c we use this.conf in the constructor
    this.baseConf = confParam;
    this.conf = new CompoundConfiguration()
      .add(confParam)
      .add(htd.getValues());
    this.rowLockWaitDuration = conf.getInt("hbase.rowlock.wait.duration",
                    DEFAULT_ROWLOCK_WAIT_DURATION);
    this.regionInfo = regionInfo;
    this.htableDescriptor = htd;
    this.rsServices = rsServices;
    this.threadWakeFrequency = conf.getLong(HConstants.THREAD_WAKE_FREQUENCY,
        10 * 1000);
    String encodedNameStr = this.regionInfo.getEncodedName();
    setHTableSpecificConf();
    this.regiondir = getRegionDir(this.tableDir, encodedNameStr);
    this.scannerReadPoints = new ConcurrentHashMap<RegionScanner, Long>();

    this.metricsRegion = new MetricsRegion(new MetricsRegionWrapperImpl(this));

    /*
     * timestamp.slop provides a server-side constraint on the timestamp. This
     * assumes that you base your TS around currentTimeMillis(). In this case,
     * throw an error to the user if the user-specified TS is newer than now +
     * slop. LATEST_TIMESTAMP == don't use this functionality
     */
    this.timestampSlop = conf.getLong(
        "hbase.hregion.keyvalue.timestamp.slop.millisecs",
        HConstants.LATEST_TIMESTAMP);

    /**
     * Timeout for the process time in processRowsWithLocks().
     * Use -1 to switch off time bound.
     */
    this.rowProcessorTimeout = conf.getLong(
        "hbase.hregion.row.processor.timeout", DEFAULT_ROW_PROCESSOR_TIMEOUT);

    if (rsServices != null) {
      this.rsAccounting = this.rsServices.getRegionServerAccounting();
      // don't initialize coprocessors if not running within a regionserver
      // TODO: revisit if coprocessors should load in other cases
      this.coprocessorHost = new RegionCoprocessorHost(this, rsServices, conf);
    }
    if (LOG.isDebugEnabled()) {
      // Write out region name as string and its encoded name.
      LOG.debug("Instantiated " + this);
    }
  }

  void setHTableSpecificConf() {
    if (this.htableDescriptor == null) return;
    long flushSize = this.htableDescriptor.getMemStoreFlushSize();

    if (flushSize == HTableDescriptor.DEFAULT_MEMSTORE_FLUSH_SIZE) {
      flushSize = conf.getLong(HConstants.HREGION_MEMSTORE_FLUSH_SIZE,
         HTableDescriptor.DEFAULT_MEMSTORE_FLUSH_SIZE);
    }
    this.memstoreFlushSize = flushSize;
    this.blockingMemStoreSize = this.memstoreFlushSize *
        conf.getLong("hbase.hregion.memstore.block.multiplier", 2);
  }

  /**
   * Initialize this region.
   * @return What the next sequence (edit) id should be.
   * @throws IOException e
   */
  public long initialize() throws IOException {
    return initialize(null);
  }

  /**
   * Initialize this region.
   *
   * @param reporter Tickle every so often if initialize is taking a while.
   * @return What the next sequence (edit) id should be.
   * @throws IOException e
   */
  public long initialize(final CancelableProgressable reporter)
  throws IOException {
    MonitoredTask status = TaskMonitor.get().createStatus("Initializing region " + this);
    long nextSeqId = -1;
    try {
      nextSeqId = initializeRegionInternals(reporter, status);
      return nextSeqId;
    } finally {
      // nextSeqid will be -1 if the initialization fails.
      // At least it will be 0 otherwise.
      if (nextSeqId == -1) {
        status
            .abort("Exception during region " + this.getRegionNameAsString() + " initialization.");
      }
    }
  }

  private long initializeRegionInternals(final CancelableProgressable reporter, MonitoredTask status)
      throws IOException, UnsupportedEncodingException {
    if (coprocessorHost != null) {
      status.setStatus("Running coprocessor pre-open hook");
      coprocessorHost.preOpen();
    }

    // Write HRI to a file in case we need to recover .META.
    status.setStatus("Writing region info on filesystem");
    checkRegioninfoOnFilesystem();

    // Remove temporary data left over from old regions
    status.setStatus("Cleaning up temporary data from old regions");
    cleanupTmpDir();

    // Load in all the HStores.
    //
    // Context: During replay we want to ensure that we do not lose any data. So, we
    // have to be conservative in how we replay logs. For each store, we calculate
    // the maxSeqId up to which the store was flushed. And, skip the edits which
    // is equal to or lower than maxSeqId for each store.
    Map<byte[], Long> maxSeqIdInStores = new TreeMap<byte[], Long>(
        Bytes.BYTES_COMPARATOR);
    long maxSeqId = -1;
    // initialized to -1 so that we pick up MemstoreTS from column families
    long maxMemstoreTS = -1;

    if (this.htableDescriptor != null &&
        !htableDescriptor.getFamilies().isEmpty()) {
      // initialize the thread pool for opening stores in parallel.
      ThreadPoolExecutor storeOpenerThreadPool =
        getStoreOpenAndCloseThreadPool(
          "StoreOpenerThread-" + this.regionInfo.getRegionNameAsString());
      CompletionService<HStore> completionService =
        new ExecutorCompletionService<HStore>(storeOpenerThreadPool);

      // initialize each store in parallel
      for (final HColumnDescriptor family : htableDescriptor.getFamilies()) {
        status.setStatus("Instantiating store for column family " + family);
        completionService.submit(new Callable<HStore>() {
          public HStore call() throws IOException {
            return instantiateHStore(tableDir, family);
          }
        });
      }
      try {
        for (int i = 0; i < htableDescriptor.getFamilies().size(); i++) {
          Future<HStore> future = completionService.take();
          HStore store = future.get();

          this.stores.put(store.getColumnFamilyName().getBytes(), store);
          // Do not include bulk loaded files when determining seqIdForReplay
          long storeSeqIdForReplay = store.getMaxSequenceId(false);
          maxSeqIdInStores.put(store.getColumnFamilyName().getBytes(),
              storeSeqIdForReplay);
          // Include bulk loaded files when determining seqIdForAssignment
          long storeSeqIdForAssignment = store.getMaxSequenceId(true);
          if (maxSeqId == -1 || storeSeqIdForAssignment > maxSeqId) {
            maxSeqId = storeSeqIdForAssignment;
          }
          long maxStoreMemstoreTS = store.getMaxMemstoreTS();
          if (maxStoreMemstoreTS > maxMemstoreTS) {
            maxMemstoreTS = maxStoreMemstoreTS;
          }
        }
      } catch (InterruptedException e) {
        throw new IOException(e);
      } catch (ExecutionException e) {
        throw new IOException(e.getCause());
      } finally {
        storeOpenerThreadPool.shutdownNow();
      }
    }
    mvcc.initialize(maxMemstoreTS + 1);
    // Recover any edits if available.
    maxSeqId = Math.max(maxSeqId, replayRecoveredEditsIfAny(
        this.regiondir, maxSeqIdInStores, reporter, status));

    status.setStatus("Cleaning up detritus from prior splits");
    // Get rid of any splits or merges that were lost in-progress.  Clean out
    // these directories here on open.  We may be opening a region that was
    // being split but we crashed in the middle of it all.
    SplitTransaction.cleanupAnySplitDetritus(this);
    FSUtils.deleteDirectory(this.fs, new Path(regiondir, MERGEDIR));
    if (this.htableDescriptor != null) {
      this.writestate.setReadOnly(this.htableDescriptor.isReadOnly());
    }

    this.writestate.flushRequested = false;
    this.writestate.compacting = 0;

    // Initialize split policy
    this.splitPolicy = RegionSplitPolicy.create(this, conf);

    this.lastFlushTime = EnvironmentEdgeManager.currentTimeMillis();
    // Use maximum of log sequenceid or that which was found in stores
    // (particularly if no recovered edits, seqid will be -1).
    long nextSeqid = maxSeqId + 1;
    LOG.info("Onlined " + this.toString() + "; next sequenceid=" + nextSeqid);

    // A region can be reopened if failed a split; reset flags
    this.closing.set(false);
    this.closed.set(false);

    if (coprocessorHost != null) {
      status.setStatus("Running coprocessor post-open hooks");
      coprocessorHost.postOpen();
    }

    status.markComplete("Region opened successfully");
    return nextSeqid;
  }

  /*
   * Move any passed HStore files into place (if any).  Used to pick up split
   * files and any merges from splits and merges dirs.
   * @param initialFiles
   * @throws IOException
   */
  static void moveInitialFilesIntoPlace(final FileSystem fs,
    final Path initialFiles, final Path regiondir)
  throws IOException {
    if (initialFiles != null && fs.exists(initialFiles)) {
      if (!fs.rename(initialFiles, regiondir)) {
        LOG.warn("Unable to rename " + initialFiles + " to " + regiondir);
      }
    }
  }

  /**
   * @return True if this region has references.
   */
  public boolean hasReferences() {
    for (Store store : this.stores.values()) {
      for (StoreFile sf : store.getStorefiles()) {
        // Found a reference, return.
        if (sf.isReference()) return true;
      }
    }
    return false;
  }

  /**
   * This function will return the HDFS blocks distribution based on the data
   * captured when HFile is created
   * @return The HDFS blocks distribution for the region.
   */
  public HDFSBlocksDistribution getHDFSBlocksDistribution() {
    HDFSBlocksDistribution hdfsBlocksDistribution =
      new HDFSBlocksDistribution();
    synchronized (this.stores) {
      for (Store store : this.stores.values()) {
        for (StoreFile sf : store.getStorefiles()) {
          HDFSBlocksDistribution storeFileBlocksDistribution =
            sf.getHDFSBlockDistribution();
          hdfsBlocksDistribution.add(storeFileBlocksDistribution);
        }
      }
    }
    return hdfsBlocksDistribution;
  }

  /**
   * This is a helper function to compute HDFS block distribution on demand
   * @param conf configuration
   * @param tableDescriptor HTableDescriptor of the table
   * @param regionEncodedName encoded name of the region
   * @return The HDFS blocks distribution for the given region.
 * @throws IOException
   */
  static public HDFSBlocksDistribution computeHDFSBlocksDistribution(
    Configuration conf, HTableDescriptor tableDescriptor,
    String regionEncodedName) throws IOException {
    HDFSBlocksDistribution hdfsBlocksDistribution =
      new HDFSBlocksDistribution();
    Path tablePath = FSUtils.getTablePath(FSUtils.getRootDir(conf),
      tableDescriptor.getName());
    FileSystem fs = tablePath.getFileSystem(conf);

    for (HColumnDescriptor family: tableDescriptor.getFamilies()) {
      Path storeHomeDir = HStore.getStoreHomedir(tablePath, regionEncodedName,
      family.getName());
      if (!fs.exists(storeHomeDir))continue;

      FileStatus[] hfilesStatus = null;
      hfilesStatus = fs.listStatus(storeHomeDir);

      for (FileStatus hfileStatus : hfilesStatus) {
        HDFSBlocksDistribution storeFileBlocksDistribution =
          FSUtils.computeHDFSBlocksDistribution(fs, hfileStatus, 0,
          hfileStatus.getLen());
        hdfsBlocksDistribution.add(storeFileBlocksDistribution);
      }
    }
    return hdfsBlocksDistribution;
  }

  public AtomicLong getMemstoreSize() {
    return memstoreSize;
  }

  /**
   * Increase the size of mem store in this region and the size of global mem
   * store
   * @param memStoreSize
   * @return the size of memstore in this region
   */
  public long addAndGetGlobalMemstoreSize(long memStoreSize) {
    if (this.rsAccounting != null) {
      rsAccounting.addAndGetGlobalMemstoreSize(memStoreSize);
    }
    return this.memstoreSize.getAndAdd(memStoreSize);
  }

  /*
   * Write out an info file under the region directory.  Useful recovering
   * mangled regions.
   * @throws IOException
   */
  private void checkRegioninfoOnFilesystem() throws IOException {
    Path regioninfoPath = new Path(this.regiondir, REGIONINFO_FILE);
    // Compose the content of the file so we can compare to length in filesystem.  If not same,
    // rewrite it (it may have been written in the old format using Writables instead of pb).  The
    // pb version is much shorter -- we write now w/o the toString version -- so checking length
    // only should be sufficient.  I don't want to read the file every time to check if it pb
    // serialized.
    byte [] content = getDotRegionInfoFileContent(this.getRegionInfo());
    boolean exists = this.fs.exists(regioninfoPath);
    FileStatus status = exists? this.fs.getFileStatus(regioninfoPath): null;
    if (status != null && status.getLen() == content.length) {
      // Then assume the content good and move on.
      return;
    }
    // Create in tmpdir and then move into place in case we crash after
    // create but before close.  If we don't successfully close the file,
    // subsequent region reopens will fail the below because create is
    // registered in NN.

    // First check to get the permissions
    FsPermission perms = FSUtils.getFilePermissions(fs, conf, HConstants.DATA_FILE_UMASK_KEY);

    // And then create the file
    Path tmpPath = new Path(getTmpDir(), REGIONINFO_FILE);

    // If datanode crashes or if the RS goes down just before the close is called while trying to
    // close the created regioninfo file in the .tmp directory then on next
    // creation we will be getting AlreadyCreatedException.
    // Hence delete and create the file if exists.
    if (FSUtils.isExists(fs, tmpPath)) {
      FSUtils.delete(fs, tmpPath, true);
    }

    FSDataOutputStream out = FSUtils.create(fs, tmpPath, perms);
    try {
      // We used to write out this file as serialized Writable followed by '\n\n' and then the
      // toString of the HRegionInfo but now we just write out the pb serialized bytes so we can
      // for sure tell whether the content has been pb'd or not just by looking at file length; the
      // pb version will be shorter.
      out.write(content);
    } finally {
      out.close();
    }
    if (exists) {
      LOG.info("Rewriting .regioninfo file at " + regioninfoPath);
      if (!fs.delete(regioninfoPath, false)) {
        throw new IOException("Unable to remove existing " + regioninfoPath);
      }
    }
    if (!fs.rename(tmpPath, regioninfoPath)) {
      throw new IOException("Unable to rename " + tmpPath + " to " + regioninfoPath);
    }
  }

  /**
   * @param hri
   * @return Content of the file we write out to the filesystem under a region
   * @throws IOException
   */
  private static byte [] getDotRegionInfoFileContent(final HRegionInfo hri) throws IOException {
    return hri.toDelimitedByteArray();
  }

  /**
   * @param fs
   * @param dir
   * @return An HRegionInfo instance gotten from the <code>.regioninfo</code> file under region dir
   * @throws IOException
   */
  public static HRegionInfo loadDotRegionInfoFileContent(final FileSystem fs, final Path dir)
  throws IOException {
    Path regioninfo = new Path(dir, HRegion.REGIONINFO_FILE);
    if (!fs.exists(regioninfo)) throw new FileNotFoundException(regioninfo.toString());
    FSDataInputStream in = fs.open(regioninfo);
    try {
      return HRegionInfo.parseFrom(in);
    } finally {
      in.close();
    }
  }

  /** @return a HRegionInfo object for this region */
  public HRegionInfo getRegionInfo() {
    return this.regionInfo;
  }

  /**
   * @return Instance of {@link RegionServerServices} used by this HRegion.
   * Can be null.
   */
  RegionServerServices getRegionServerServices() {
    return this.rsServices;
  }

  /** @return readRequestsCount for this region */
  long getReadRequestsCount() {
    return this.readRequestsCount.get();
  }

  /** @return writeRequestsCount for this region */
  long getWriteRequestsCount() {
    return this.writeRequestsCount.get();
  }

  MetricsRegion getMetrics() {
    return metricsRegion;
  }

  /** @return true if region is closed */
  public boolean isClosed() {
    return this.closed.get();
  }

  /**
   * @return True if closing process has started.
   */
  public boolean isClosing() {
    return this.closing.get();
  }

  /** @return true if region is available (not closed and not closing) */
  public boolean isAvailable() {
    return !isClosed() && !isClosing();
  }

  /** @return true if region is splittable */
  public boolean isSplittable() {
    return isAvailable() && !hasReferences();
  }

  boolean areWritesEnabled() {
    synchronized(this.writestate) {
      return this.writestate.writesEnabled;
    }
  }

   public MultiVersionConsistencyControl getMVCC() {
     return mvcc;
   }

  /**
   * Close down this HRegion.  Flush the cache, shut down each HStore, don't
   * service any more calls.
   *
   * <p>This method could take some time to execute, so don't call it from a
   * time-sensitive thread.
   *
   * @return Vector of all the storage files that the HRegion's component
   * HStores make use of.  It's a list of all HStoreFile objects. Returns empty
   * vector if already closed and null if judged that it should not close.
   *
   * @throws IOException e
   */
  public List<StoreFile> close() throws IOException {
    return close(false);
  }

  private final Object closeLock = new Object();

  /**
   * Close down this HRegion.  Flush the cache unless abort parameter is true,
   * Shut down each HStore, don't service any more calls.
   *
   * This method could take some time to execute, so don't call it from a
   * time-sensitive thread.
   *
   * @param abort true if server is aborting (only during testing)
   * @return Vector of all the storage files that the HRegion's component
   * HStores make use of.  It's a list of HStoreFile objects.  Can be null if
   * we are not to close at this time or we are already closed.
   *
   * @throws IOException e
   */
  public List<StoreFile> close(final boolean abort) throws IOException {
    // Only allow one thread to close at a time. Serialize them so dual
    // threads attempting to close will run up against each other.
    MonitoredTask status = TaskMonitor.get().createStatus(
        "Closing region " + this +
        (abort ? " due to abort" : ""));

    status.setStatus("Waiting for close lock");
    try {
      synchronized (closeLock) {
        return doClose(abort, status);
      }
    } finally {
      status.cleanup();
    }
  }

  private List<StoreFile> doClose(
      final boolean abort, MonitoredTask status)
  throws IOException {
    if (isClosed()) {
      LOG.warn("Region " + this + " already closed");
      return null;
    }

    if (coprocessorHost != null) {
      status.setStatus("Running coprocessor pre-close hooks");
      this.coprocessorHost.preClose(abort);
    }

    status.setStatus("Disabling compacts and flushes for region");
    boolean wasFlushing = false;
    synchronized (writestate) {
      // Disable compacting and flushing by background threads for this
      // region.
      writestate.writesEnabled = false;
      wasFlushing = writestate.flushing;
      LOG.debug("Closing " + this + ": disabling compactions & flushes");
      waitForFlushesAndCompactions();
    }
    // If we were not just flushing, is it worth doing a preflush...one
    // that will clear out of the bulk of the memstore before we put up
    // the close flag?
    if (!abort && !wasFlushing && worthPreFlushing()) {
      status.setStatus("Pre-flushing region before close");
      LOG.info("Running close preflush of " + this.getRegionNameAsString());
      internalFlushcache(status);
    }

    this.closing.set(true);
    status.setStatus("Disabling writes for close");
    lock.writeLock().lock();
    try {
      if (this.isClosed()) {
        status.abort("Already got closed by another process");
        // SplitTransaction handles the null
        return null;
      }
      LOG.debug("Updates disabled for region " + this);
      // Don't flush the cache if we are aborting
      if (!abort) {
        internalFlushcache(status);
      }

      List<StoreFile> result = new ArrayList<StoreFile>();
      if (!stores.isEmpty()) {
        // initialize the thread pool for closing stores in parallel.
        ThreadPoolExecutor storeCloserThreadPool =
          getStoreOpenAndCloseThreadPool("StoreCloserThread-"
            + this.regionInfo.getRegionNameAsString());
        CompletionService<ImmutableList<StoreFile>> completionService =
          new ExecutorCompletionService<ImmutableList<StoreFile>>(
            storeCloserThreadPool);

        // close each store in parallel
        for (final Store store : stores.values()) {
          completionService
              .submit(new Callable<ImmutableList<StoreFile>>() {
                public ImmutableList<StoreFile> call() throws IOException {
                  return store.close();
                }
              });
        }
        try {
          for (int i = 0; i < stores.size(); i++) {
            Future<ImmutableList<StoreFile>> future = completionService
                .take();
            ImmutableList<StoreFile> storeFileList = future.get();
            result.addAll(storeFileList);
          }
        } catch (InterruptedException e) {
          throw new IOException(e);
        } catch (ExecutionException e) {
          throw new IOException(e.getCause());
        } finally {
          storeCloserThreadPool.shutdownNow();
        }
      }
      this.closed.set(true);

      if (coprocessorHost != null) {
        status.setStatus("Running coprocessor post-close hooks");
        this.coprocessorHost.postClose(abort);
      }
      this.metricsRegion.close();
      status.markComplete("Closed");
      LOG.info("Closed " + this);
      return result;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Wait for all current flushes and compactions of the region to complete.
   * <p>
   * Exposed for TESTING.
   */
  public void waitForFlushesAndCompactions() {
    synchronized (writestate) {
      while (writestate.compacting > 0 || writestate.flushing) {
        LOG.debug("waiting for " + writestate.compacting + " compactions"
            + (writestate.flushing ? " & cache flush" : "") + " to complete for region " + this);
        try {
          writestate.wait();
        } catch (InterruptedException iex) {
          // essentially ignore and propagate the interrupt back up
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  protected ThreadPoolExecutor getStoreOpenAndCloseThreadPool(
      final String threadNamePrefix) {
    int numStores = Math.max(1, this.htableDescriptor.getFamilies().size());
    int maxThreads = Math.min(numStores,
        conf.getInt(HConstants.HSTORE_OPEN_AND_CLOSE_THREADS_MAX,
            HConstants.DEFAULT_HSTORE_OPEN_AND_CLOSE_THREADS_MAX));
    return getOpenAndCloseThreadPool(maxThreads, threadNamePrefix);
  }

  protected ThreadPoolExecutor getStoreFileOpenAndCloseThreadPool(
      final String threadNamePrefix) {
    int numStores = Math.max(1, this.htableDescriptor.getFamilies().size());
    int maxThreads = Math.max(1,
        conf.getInt(HConstants.HSTORE_OPEN_AND_CLOSE_THREADS_MAX,
            HConstants.DEFAULT_HSTORE_OPEN_AND_CLOSE_THREADS_MAX)
            / numStores);
    return getOpenAndCloseThreadPool(maxThreads, threadNamePrefix);
  }

  static ThreadPoolExecutor getOpenAndCloseThreadPool(int maxThreads,
      final String threadNamePrefix) {
    return Threads.getBoundedCachedThreadPool(maxThreads, 30L, TimeUnit.SECONDS,
      new ThreadFactory() {
        private int count = 1;

        public Thread newThread(Runnable r) {
          return new Thread(r, threadNamePrefix + "-" + count++);
        }
      });
  }

   /**
    * @return True if its worth doing a flush before we put up the close flag.
    */
  private boolean worthPreFlushing() {
    return this.memstoreSize.get() >
      this.conf.getLong("hbase.hregion.preclose.flush.size", 1024 * 1024 * 5);
  }

  //////////////////////////////////////////////////////////////////////////////
  // HRegion accessors
  //////////////////////////////////////////////////////////////////////////////

  /** @return start key for region */
  public byte [] getStartKey() {
    return this.regionInfo.getStartKey();
  }

  /** @return end key for region */
  public byte [] getEndKey() {
    return this.regionInfo.getEndKey();
  }

  /** @return region id */
  public long getRegionId() {
    return this.regionInfo.getRegionId();
  }

  /** @return region name */
  public byte [] getRegionName() {
    return this.regionInfo.getRegionName();
  }

  /** @return region name as string for logging */
  public String getRegionNameAsString() {
    return this.regionInfo.getRegionNameAsString();
  }

  /** @return HTableDescriptor for this region */
  public HTableDescriptor getTableDesc() {
    return this.htableDescriptor;
  }

  /** @return HLog in use for this region */
  public HLog getLog() {
    return this.log;
  }

  /**
   * A split takes the config from the parent region & passes it to the daughter
   * region's constructor. If 'conf' was passed, you would end up using the HTD
   * of the parent region in addition to the new daughter HTD. Pass 'baseConf'
   * to the daughter regions to avoid this tricky dedupe problem.
   * @return Configuration object
   */
  Configuration getBaseConf() {
    return this.baseConf;
  }

  /** @return region directory Path */
  public Path getRegionDir() {
    return this.regiondir;
  }

  /**
   * Computes the Path of the HRegion
   *
   * @param tabledir qualified path for table
   * @param name ENCODED region name
   * @return Path of HRegion directory
   */
  public static Path getRegionDir(final Path tabledir, final String name) {
    return new Path(tabledir, name);
  }

  /** @return FileSystem being used by this region */
  public FileSystem getFilesystem() {
    return this.fs;
  }

  /** @return the last time the region was flushed */
  public long getLastFlushTime() {
    return this.lastFlushTime;
  }

  /** @return info about the last flushes <time, size> */
  public List<Pair<Long,Long>> getRecentFlushInfo() {
    List<Pair<Long,Long>> ret = null;
    this.lock.readLock().lock();
    try {
      ret = this.recentFlushes;
      this.recentFlushes = new ArrayList<Pair<Long,Long>>();
    } finally {
      this.lock.readLock().unlock();
    }
    return ret;
  }

  //////////////////////////////////////////////////////////////////////////////
  // HRegion maintenance.
  //
  // These methods are meant to be called periodically by the HRegionServer for
  // upkeep.
  //////////////////////////////////////////////////////////////////////////////

  /** @return returns size of largest HStore. */
  public long getLargestHStoreSize() {
    long size = 0;
    for (Store h : stores.values()) {
      long storeSize = h.getSize();
      if (storeSize > size) {
        size = storeSize;
      }
    }
    return size;
  }

  /*
   * Do preparation for pending compaction.
   * @throws IOException
   */
  void doRegionCompactionPrep() throws IOException {
  }

  /*
   * Removes the temporary directory for this Store.
   */
  private void cleanupTmpDir() throws IOException {
    FSUtils.deleteDirectory(this.fs, getTmpDir());
  }

  /**
   * Get the temporary directory for this region. This directory
   * will have its contents removed when the region is reopened.
   */
  Path getTmpDir() {
    return new Path(getRegionDir(), REGION_TEMP_SUBDIR);
  }

  void triggerMajorCompaction() {
    for (Store h : stores.values()) {
      h.triggerMajorCompaction();
    }
  }

  /**
   * This is a helper function that compact all the stores synchronously
   * It is used by utilities and testing
   *
   * @param majorCompaction True to force a major compaction regardless of thresholds
   * @throws IOException e
   */
  public void compactStores(final boolean majorCompaction)
  throws IOException {
    if (majorCompaction) {
      this.triggerMajorCompaction();
    }
    compactStores();
  }

  /**
   * This is a helper function that compact all the stores synchronously
   * It is used by utilities and testing
   *
   * @throws IOException e
   */
  public void compactStores() throws IOException {
    for (Store s : getStores().values()) {
      CompactionRequest cr = s.requestCompaction();
      if(cr != null) {
        try {
          compact(cr);
        } finally {
          s.finishRequest(cr);
        }
      }
    }
  }

  /*
   * Called by compaction thread and after region is opened to compact the
   * HStores if necessary.
   *
   * <p>This operation could block for a long time, so don't call it from a
   * time-sensitive thread.
   *
   * Note that no locking is necessary at this level because compaction only
   * conflicts with a region split, and that cannot happen because the region
   * server does them sequentially and not in parallel.
   *
   * @param cr Compaction details, obtained by requestCompaction()
   * @return whether the compaction completed
   * @throws IOException e
   */
  public boolean compact(CompactionRequest cr)
  throws IOException {
    if (cr == null) {
      return false;
    }
    if (this.closing.get() || this.closed.get()) {
      LOG.debug("Skipping compaction on " + this + " because closing/closed");
      return false;
    }
    Preconditions.checkArgument(cr.getHRegion().equals(this));
    MonitoredTask status = null;
    lock.readLock().lock();
    try {
      status = TaskMonitor.get().createStatus(
        "Compacting " + cr.getStore() + " in " + this);
      if (this.closed.get()) {
        LOG.debug("Skipping compaction on " + this + " because closed");
        return false;
      }
      boolean decr = true;
      try {
        synchronized (writestate) {
          if (writestate.writesEnabled) {
            ++writestate.compacting;
          } else {
            String msg = "NOT compacting region " + this + ". Writes disabled.";
            LOG.info(msg);
            status.abort(msg);
            decr = false;
            return false;
          }
        }
        LOG.info("Starting compaction on " + cr.getStore() + " in region "
            + this + (cr.getCompactSelection().isOffPeakCompaction()?" as an off-peak compaction":""));
        doRegionCompactionPrep();
        try {
          status.setStatus("Compacting store " + cr.getStore());
          cr.getStore().compact(cr);
        } catch (InterruptedIOException iioe) {
          String msg = "compaction interrupted";
          LOG.info(msg, iioe);
          status.abort(msg);
          return false;
        }
      } finally {
        if (decr) {
          synchronized (writestate) {
            --writestate.compacting;
            if (writestate.compacting <= 0) {
              writestate.notifyAll();
            }
          }
        }
      }
      status.markComplete("Compaction complete");
      return true;
    } finally {
      try {
        if (status != null) status.cleanup();
      } finally {
        lock.readLock().unlock();
      }
    }
  }

  /**
   * Flush the cache.
   *
   * When this method is called the cache will be flushed unless:
   * <ol>
   *   <li>the cache is empty</li>
   *   <li>the region is closed.</li>
   *   <li>a flush is already in progress</li>
   *   <li>writes are disabled</li>
   * </ol>
   *
   * <p>This method may block for some time, so it should not be called from a
   * time-sensitive thread.
   *
   * @return true if cache was flushed
   *
   * @throws IOException general io exceptions
   * @throws DroppedSnapshotException Thrown when replay of hlog is required
   * because a Snapshot was not properly persisted.
   */
  public boolean flushcache() throws IOException {
    // fail-fast instead of waiting on the lock
    if (this.closing.get()) {
      LOG.debug("Skipping flush on " + this + " because closing");
      return false;
    }
    MonitoredTask status = TaskMonitor.get().createStatus("Flushing " + this);
    status.setStatus("Acquiring readlock on region");
    lock.readLock().lock();
    try {
      if (this.closed.get()) {
        LOG.debug("Skipping flush on " + this + " because closed");
        status.abort("Skipped: closed");
        return false;
      }
      if (coprocessorHost != null) {
        status.setStatus("Running coprocessor pre-flush hooks");
        coprocessorHost.preFlush();
      }
      if (numPutsWithoutWAL.get() > 0) {
        numPutsWithoutWAL.set(0);
        dataInMemoryWithoutWAL.set(0);
      }
      synchronized (writestate) {
        if (!writestate.flushing && writestate.writesEnabled) {
          this.writestate.flushing = true;
        } else {
          if (LOG.isDebugEnabled()) {
            LOG.debug("NOT flushing memstore for region " + this
                + ", flushing=" + writestate.flushing + ", writesEnabled="
                + writestate.writesEnabled);
          }
          status.abort("Not flushing since "
              + (writestate.flushing ? "already flushing"
                  : "writes not enabled"));
          return false;
        }
      }
      try {
        boolean result = internalFlushcache(status);

        if (coprocessorHost != null) {
          status.setStatus("Running post-flush coprocessor hooks");
          coprocessorHost.postFlush();
        }

        status.markComplete("Flush successful");
        return result;
      } finally {
        synchronized (writestate) {
          writestate.flushing = false;
          this.writestate.flushRequested = false;
          writestate.notifyAll();
        }
      }
    } finally {
      lock.readLock().unlock();
      status.cleanup();
    }
  }

  /**
   * Flush the memstore.
   *
   * Flushing the memstore is a little tricky. We have a lot of updates in the
   * memstore, all of which have also been written to the log. We need to
   * write those updates in the memstore out to disk, while being able to
   * process reads/writes as much as possible during the flush operation. Also,
   * the log has to state clearly the point in time at which the memstore was
   * flushed. (That way, during recovery, we know when we can rely on the
   * on-disk flushed structures and when we have to recover the memstore from
   * the log.)
   *
   * <p>So, we have a three-step process:
   *
   * <ul><li>A. Flush the memstore to the on-disk stores, noting the current
   * sequence ID for the log.<li>
   *
   * <li>B. Write a FLUSHCACHE-COMPLETE message to the log, using the sequence
   * ID that was current at the time of memstore-flush.</li>
   *
   * <li>C. Get rid of the memstore structures that are now redundant, as
   * they've been flushed to the on-disk HStores.</li>
   * </ul>
   * <p>This method is protected, but can be accessed via several public
   * routes.
   *
   * <p> This method may block for some time.
   * @param status
   *
   * @return true if the region needs compacting
   *
   * @throws IOException general io exceptions
   * @throws DroppedSnapshotException Thrown when replay of hlog is required
   * because a Snapshot was not properly persisted.
   */
  protected boolean internalFlushcache(MonitoredTask status)
      throws IOException {
    return internalFlushcache(this.log, -1, status);
  }

  /**
   * @param wal Null if we're NOT to go via hlog/wal.
   * @param myseqid The seqid to use if <code>wal</code> is null writing out
   * flush file.
   * @param status
   * @return true if the region needs compacting
   * @throws IOException
   * @see #internalFlushcache(MonitoredTask)
   */
  protected boolean internalFlushcache(
      final HLog wal, final long myseqid, MonitoredTask status)
  throws IOException {
    final long startTime = EnvironmentEdgeManager.currentTimeMillis();
    // Clear flush flag.
    // Record latest flush time
    this.lastFlushTime = startTime;
    // If nothing to flush, return and avoid logging start/stop flush.
    if (this.memstoreSize.get() <= 0) {
      return false;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Started memstore flush for " + this +
        ", current region memstore size " +
        StringUtils.humanReadableInt(this.memstoreSize.get()) +
        ((wal != null)? "": "; wal is null, using passed sequenceid=" + myseqid));
    }

    // Stop updates while we snapshot the memstore of all stores. We only have
    // to do this for a moment.  Its quick.  The subsequent sequence id that
    // goes into the HLog after we've flushed all these snapshots also goes
    // into the info file that sits beside the flushed files.
    // We also set the memstore size to zero here before we allow updates
    // again so its value will represent the size of the updates received
    // during the flush
    long sequenceId = -1L;
    MultiVersionConsistencyControl.WriteEntry w = null;

    // We have to take a write lock during snapshot, or else a write could
    // end up in both snapshot and memstore (makes it difficult to do atomic
    // rows then)
    status.setStatus("Obtaining lock to block concurrent updates");
    this.updatesLock.writeLock().lock();
    long flushsize = this.memstoreSize.get();
    status.setStatus("Preparing to flush by snapshotting stores");
    List<StoreFlusher> storeFlushers = new ArrayList<StoreFlusher>(stores.size());
    long completeSeqId = -1L;
    try {
      // Record the mvcc for all transactions in progress.
      w = mvcc.beginMemstoreInsert();
      mvcc.advanceMemstore(w);

      sequenceId = (wal == null)? myseqid:
        wal.startCacheFlush(this.regionInfo.getEncodedNameAsBytes());
      completeSeqId = this.getCompleteCacheFlushSequenceId(sequenceId);
      for (Store s : stores.values()) {
        storeFlushers.add(s.getStoreFlusher(completeSeqId));
      }

      // prepare flush (take a snapshot)
      for (StoreFlusher flusher : storeFlushers) {
        flusher.prepare();
      }
    } finally {
      this.updatesLock.writeLock().unlock();
    }
    String s = "Finished snapshotting " + this +
      ", commencing wait for mvcc, flushsize=" + flushsize;
    status.setStatus(s);
    LOG.debug(s);

    // wait for all in-progress transactions to commit to HLog before
    // we can start the flush. This prevents
    // uncommitted transactions from being written into HFiles.
    // We have to block before we start the flush, otherwise keys that
    // were removed via a rollbackMemstore could be written to Hfiles.
    mvcc.waitForRead(w);

    status.setStatus("Flushing stores");
    LOG.debug("Finished snapshotting, commencing flushing stores");

    // Any failure from here on out will be catastrophic requiring server
    // restart so hlog content can be replayed and put back into the memstore.
    // Otherwise, the snapshot content while backed up in the hlog, it will not
    // be part of the current running servers state.
    boolean compactionRequested = false;
    try {
      // A.  Flush memstore to all the HStores.
      // Keep running vector of all store files that includes both old and the
      // just-made new flush store file. The new flushed file is still in the
      // tmp directory.

      for (StoreFlusher flusher : storeFlushers) {
        flusher.flushCache(status);
      }

      // Switch snapshot (in memstore) -> new hfile (thus causing
      // all the store scanners to reset/reseek).
      for (StoreFlusher flusher : storeFlushers) {
        boolean needsCompaction = flusher.commit(status);
        if (needsCompaction) {
          compactionRequested = true;
        }
      }
      storeFlushers.clear();

      // Set down the memstore size by amount of flush.
      this.addAndGetGlobalMemstoreSize(-flushsize);
    } catch (Throwable t) {
      // An exception here means that the snapshot was not persisted.
      // The hlog needs to be replayed so its content is restored to memstore.
      // Currently, only a server restart will do this.
      // We used to only catch IOEs but its possible that we'd get other
      // exceptions -- e.g. HBASE-659 was about an NPE -- so now we catch
      // all and sundry.
      if (wal != null) {
        wal.abortCacheFlush(this.regionInfo.getEncodedNameAsBytes());
      }
      DroppedSnapshotException dse = new DroppedSnapshotException("region: " +
          Bytes.toStringBinary(getRegionName()));
      dse.initCause(t);
      status.abort("Flush failed: " + StringUtils.stringifyException(t));
      throw dse;
    }

    // If we get to here, the HStores have been written. If we get an
    // error in completeCacheFlush it will release the lock it is holding

    // B.  Write a FLUSHCACHE-COMPLETE message to the log.
    //     This tells future readers that the HStores were emitted correctly,
    //     and that all updates to the log for this regionName that have lower
    //     log-sequence-ids can be safely ignored.
    if (wal != null) {
      wal.completeCacheFlush(this.regionInfo.getEncodedNameAsBytes(),
        regionInfo.getTableName(), completeSeqId,
        this.getRegionInfo().isMetaRegion());
    }

    // Update the last flushed sequence id for region
    if (this.rsServices != null) {
      completeSequenceId = completeSeqId;
    }

    // C. Finally notify anyone waiting on memstore to clear:
    // e.g. checkResources().
    synchronized (this) {
      notifyAll(); // FindBugs NN_NAKED_NOTIFY
    }

    long time = EnvironmentEdgeManager.currentTimeMillis() - startTime;
    long memstoresize = this.memstoreSize.get();
    String msg = "Finished memstore flush of ~" +
      StringUtils.humanReadableInt(flushsize) + "/" + flushsize +
      ", currentsize=" +
      StringUtils.humanReadableInt(memstoresize) + "/" + memstoresize +
      " for region " + this + " in " + time + "ms, sequenceid=" + sequenceId +
      ", compaction requested=" + compactionRequested +
      ((wal == null)? "; wal=null": "");
    LOG.info(msg);
    status.setStatus(msg);
    this.recentFlushes.add(new Pair<Long,Long>(time/1000, flushsize));

    return compactionRequested;
  }

   /**
   * Get the sequence number to be associated with this cache flush. Used by
   * TransactionalRegion to not complete pending transactions.
   *
   *
   * @param currentSequenceId
   * @return sequence id to complete the cache flush with
   */
  protected long getCompleteCacheFlushSequenceId(long currentSequenceId) {
    return currentSequenceId;
  }

  //////////////////////////////////////////////////////////////////////////////
  // get() methods for client use.
  //////////////////////////////////////////////////////////////////////////////
  /**
   * Return all the data for the row that matches <i>row</i> exactly,
   * or the one that immediately preceeds it, at or immediately before
   * <i>ts</i>.
   *
   * @param row row key
   * @return map of values
   * @throws IOException
   */
  Result getClosestRowBefore(final byte [] row)
  throws IOException{
    return getClosestRowBefore(row, HConstants.CATALOG_FAMILY);
  }

  /**
   * Return all the data for the row that matches <i>row</i> exactly,
   * or the one that immediately preceeds it, at or immediately before
   * <i>ts</i>.
   *
   * @param row row key
   * @param family column family to find on
   * @return map of values
   * @throws IOException read exceptions
   */
  public Result getClosestRowBefore(final byte [] row, final byte [] family)
  throws IOException {
    if (coprocessorHost != null) {
      Result result = new Result();
      if (coprocessorHost.preGetClosestRowBefore(row, family, result)) {
        return result;
      }
    }
    // look across all the HStores for this region and determine what the
    // closest key is across all column families, since the data may be sparse
    checkRow(row, "getClosestRowBefore");
    startRegionOperation();
    this.readRequestsCount.increment();
    try {
      Store store = getStore(family);
      // get the closest key. (HStore.getRowKeyAtOrBefore can return null)
      KeyValue key = store.getRowKeyAtOrBefore(row);
      Result result = null;
      if (key != null) {
        Get get = new Get(key.getRow());
        get.addFamily(family);
        result = get(get, null);
      }
      if (coprocessorHost != null) {
        coprocessorHost.postGetClosestRowBefore(row, family, result);
      }
      return result;
    } finally {
      closeRegionOperation();
    }
  }

  /**
   * Return an iterator that scans over the HRegion, returning the indicated
   * columns and rows specified by the {@link Scan}.
   * <p>
   * This Iterator must be closed by the caller.
   *
   * @param scan configured {@link Scan}
   * @return RegionScanner
   * @throws IOException read exceptions
   */
  public RegionScanner getScanner(Scan scan) throws IOException {
   return getScanner(scan, null);
  }

  void prepareScanner(Scan scan) throws IOException {
    if(!scan.hasFamilies()) {
      // Adding all families to scanner
      for(byte[] family: this.htableDescriptor.getFamiliesKeys()){
        scan.addFamily(family);
      }
    }
  }

  protected RegionScanner getScanner(Scan scan,
      List<KeyValueScanner> additionalScanners) throws IOException {
    startRegionOperation();
    try {
      // Verify families are all valid
      prepareScanner(scan);
      if(scan.hasFamilies()) {
        for(byte [] family : scan.getFamilyMap().keySet()) {
          checkFamily(family);
        }
      }
      return instantiateRegionScanner(scan, additionalScanners);
    } finally {
      closeRegionOperation();
    }
  }

  protected RegionScanner instantiateRegionScanner(Scan scan,
      List<KeyValueScanner> additionalScanners) throws IOException {
    return new RegionScannerImpl(scan, additionalScanners);
  }

  /*
   * @param delete The passed delete is modified by this method. WARNING!
   */
  void prepareDelete(Delete delete) throws IOException {
    // Check to see if this is a deleteRow insert
    if(delete.getFamilyMap().isEmpty()){
      for(byte [] family : this.htableDescriptor.getFamiliesKeys()){
        // Don't eat the timestamp
        delete.deleteFamily(family, delete.getTimeStamp());
      }
    } else {
      for(byte [] family : delete.getFamilyMap().keySet()) {
        if(family == null) {
          throw new NoSuchColumnFamilyException("Empty family is invalid");
        }
        checkFamily(family);
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // set() methods for client use.
  //////////////////////////////////////////////////////////////////////////////
  /**
   * @param delete delete object
   * @param lockid existing lock id, or null for grab a lock
   * @param writeToWAL append to the write ahead lock or not
   * @throws IOException read exceptions
   */
  public void delete(Delete delete, Integer lockid, boolean writeToWAL)
  throws IOException {
    checkReadOnly();
    checkResources();
    Integer lid = null;
    startRegionOperation();
    this.writeRequestsCount.increment();
    try {
      byte [] row = delete.getRow();
      // If we did not pass an existing row lock, obtain a new one
      lid = getLock(lockid, row, true);

      try {
        // All edits for the given row (across all column families) must happen atomically.
        doBatchMutate(delete, lid);
      } finally {
        if(lockid == null) releaseRowLock(lid);
      }
    } finally {
      closeRegionOperation();
    }
  }

  /**
   * This is used only by unit tests. Not required to be a public API.
   * @param familyMap map of family to edits for the given family.
   * @param writeToWAL
   * @throws IOException
   */
  void delete(Map<byte[], List<KeyValue>> familyMap, UUID clusterId,
      boolean writeToWAL) throws IOException {
    Delete delete = new Delete(HConstants.EMPTY_BYTE_ARRAY);
    delete.setFamilyMap(familyMap);
    delete.setClusterId(clusterId);
    delete.setWriteToWAL(writeToWAL);
    doBatchMutate(delete, null);
  }

  /**
   * Setup correct timestamps in the KVs in Delete object.
   * Caller should have the row and region locks.
   * @param familyMap
   * @param now
   * @throws IOException
   */
  void prepareDeleteTimestamps(Map<byte[], List<KeyValue>> familyMap, byte[] byteNow)
      throws IOException {
    for (Map.Entry<byte[], List<KeyValue>> e : familyMap.entrySet()) {

      byte[] family = e.getKey();
      List<KeyValue> kvs = e.getValue();
      Map<byte[], Integer> kvCount = new TreeMap<byte[], Integer>(Bytes.BYTES_COMPARATOR);

      for (KeyValue kv: kvs) {
        //  Check if time is LATEST, change to time of most recent addition if so
        //  This is expensive.
        if (kv.isLatestTimestamp() && kv.isDeleteType()) {
          byte[] qual = kv.getQualifier();
          if (qual == null) qual = HConstants.EMPTY_BYTE_ARRAY;

          Integer count = kvCount.get(qual);
          if (count == null) {
            kvCount.put(qual, 1);
          } else {
            kvCount.put(qual, count + 1);
          }
          count = kvCount.get(qual);

          Get get = new Get(kv.getRow());
          get.setMaxVersions(count);
          get.addColumn(family, qual);

          List<KeyValue> result = get(get, false);

          if (result.size() < count) {
            // Nothing to delete
            kv.updateLatestStamp(byteNow);
            continue;
          }
          if (result.size() > count) {
            throw new RuntimeException("Unexpected size: " + result.size());
          }
          KeyValue getkv = result.get(count - 1);
          Bytes.putBytes(kv.getBuffer(), kv.getTimestampOffset(),
              getkv.getBuffer(), getkv.getTimestampOffset(), Bytes.SIZEOF_LONG);
        } else {
          kv.updateLatestStamp(byteNow);
        }
      }
    }
  }

  /**
   * @param put
   * @throws IOException
   */
  public void put(Put put) throws IOException {
    this.put(put, null, put.getWriteToWAL());
  }

  /**
   * @param put
   * @param writeToWAL
   * @throws IOException
   */
  public void put(Put put, boolean writeToWAL) throws IOException {
    this.put(put, null, writeToWAL);
  }

  /**
   * @param put
   * @param lockid
   * @throws IOException
   */
  public void put(Put put, Integer lockid) throws IOException {
    this.put(put, lockid, put.getWriteToWAL());
  }



  /**
   * @param put
   * @param lockid
   * @param writeToWAL
   * @throws IOException
   */
  public void put(Put put, Integer lockid, boolean writeToWAL)
  throws IOException {
    checkReadOnly();

    // Do a rough check that we have resources to accept a write.  The check is
    // 'rough' in that between the resource check and the call to obtain a
    // read lock, resources may run out.  For now, the thought is that this
    // will be extremely rare; we'll deal with it when it happens.
    checkResources();
    startRegionOperation();
    this.writeRequestsCount.increment();
    try {
      // We obtain a per-row lock, so other clients will block while one client
      // performs an update. The read lock is released by the client calling
      // #commit or #abort or if the HRegionServer lease on the lock expires.
      // See HRegionServer#RegionListener for how the expire on HRegionServer
      // invokes a HRegion#abort.
      byte [] row = put.getRow();
      // If we did not pass an existing row lock, obtain a new one
      Integer lid = getLock(lockid, row, true);

      try {
        // All edits for the given row (across all column families) must happen atomically.
        doBatchMutate(put, lid);
      } finally {
        if(lockid == null) releaseRowLock(lid);
      }
    } finally {
      closeRegionOperation();
    }
  }

  /**
   * Struct-like class that tracks the progress of a batch operation,
   * accumulating status codes and tracking the index at which processing
   * is proceeding.
   */
  private static class BatchOperationInProgress<T> {
    T[] operations;
    int nextIndexToProcess = 0;
    OperationStatus[] retCodeDetails;
    WALEdit[] walEditsFromCoprocessors;

    public BatchOperationInProgress(T[] operations) {
      this.operations = operations;
      this.retCodeDetails = new OperationStatus[operations.length];
      this.walEditsFromCoprocessors = new WALEdit[operations.length];
      Arrays.fill(this.retCodeDetails, OperationStatus.NOT_RUN);
    }

    public boolean isDone() {
      return nextIndexToProcess == operations.length;
    }
  }

  /**
   * Perform a batch put with no pre-specified locks
   * @see HRegion#put(Pair[])
   */
  public OperationStatus[] put(Put[] puts) throws IOException {
    @SuppressWarnings("unchecked")
    Pair<Mutation, Integer> putsAndLocks[] = new Pair[puts.length];

    for (int i = 0; i < puts.length; i++) {
      putsAndLocks[i] = new Pair<Mutation, Integer>(puts[i], null);
    }
    return batchMutate(putsAndLocks);
  }

  /**
   * Perform a batch of mutations.
   * It supports only Put and Delete mutations and will ignore other types passed.
   * @param mutationsAndLocks
   *          the list of mutations paired with their requested lock IDs.
   * @return an array of OperationStatus which internally contains the
   *         OperationStatusCode and the exceptionMessage if any.
   * @throws IOException
   */
  public OperationStatus[] batchMutate(
      Pair<Mutation, Integer>[] mutationsAndLocks) throws IOException {
    BatchOperationInProgress<Pair<Mutation, Integer>> batchOp =
      new BatchOperationInProgress<Pair<Mutation,Integer>>(mutationsAndLocks);

    boolean initialized = false;

    while (!batchOp.isDone()) {
      checkReadOnly();
      checkResources();

      long newSize;
      startRegionOperation();

      try {
        if (!initialized) {
          this.writeRequestsCount.increment();
          doPreMutationHook(batchOp);
          initialized = true;
        }
        long addedSize = doMiniBatchMutation(batchOp);
        newSize = this.addAndGetGlobalMemstoreSize(addedSize);
      } finally {
        closeRegionOperation();
      }
      if (isFlushSize(newSize)) {
        requestFlush();
      }
    }
    return batchOp.retCodeDetails;
  }

  private void doPreMutationHook(BatchOperationInProgress<Pair<Mutation, Integer>> batchOp)
      throws IOException {
    /* Run coprocessor pre hook outside of locks to avoid deadlock */
    WALEdit walEdit = new WALEdit();
    if (coprocessorHost != null) {
      for (int i = 0 ; i < batchOp.operations.length; i++) {
        Pair<Mutation, Integer> nextPair = batchOp.operations[i];
        Mutation m = nextPair.getFirst();
        if (m instanceof Put) {
          if (coprocessorHost.prePut((Put) m, walEdit, m.getWriteToWAL())) {
            // pre hook says skip this Put
            // mark as success and skip in doMiniBatchMutation
            batchOp.retCodeDetails[i] = OperationStatus.SUCCESS;
          }
        } else if (m instanceof Delete) {
          if (coprocessorHost.preDelete((Delete) m, walEdit, m.getWriteToWAL())) {
            // pre hook says skip this Delete
            // mark as success and skip in doMiniBatchMutation
            batchOp.retCodeDetails[i] = OperationStatus.SUCCESS;
          }
        } else {
          // In case of passing Append mutations along with the Puts and Deletes in batchMutate
          // mark the operation return code as failure so that it will not be considered in
          // the doMiniBatchMutation
          batchOp.retCodeDetails[i] = new OperationStatus(OperationStatusCode.FAILURE,
              "Put/Delete mutations only supported in batchMutate() now");
        }
        if (!walEdit.isEmpty()) {
          batchOp.walEditsFromCoprocessors[i] = walEdit;
          walEdit = new WALEdit();
        }
      }
    }
  }


  @SuppressWarnings("unchecked")
  private long doMiniBatchMutation(
    BatchOperationInProgress<Pair<Mutation, Integer>> batchOp) throws IOException {

    // variable to note if all Put items are for the same CF -- metrics related
    boolean putsCfSetConsistent = true;
    //The set of columnFamilies first seen for Put.
    Set<byte[]> putsCfSet = null;
    // variable to note if all Delete items are for the same CF -- metrics related
    boolean deletesCfSetConsistent = true;
    //The set of columnFamilies first seen for Delete.
    Set<byte[]> deletesCfSet = null;

    WALEdit walEdit = new WALEdit();

    long startTimeMs = EnvironmentEdgeManager.currentTimeMillis();


    MultiVersionConsistencyControl.WriteEntry w = null;
    long txid = 0;
    boolean walSyncSuccessful = false;
    boolean locked = false;

    /** Keep track of the locks we hold so we can release them in finally clause */
    List<Integer> acquiredLocks = Lists.newArrayListWithCapacity(batchOp.operations.length);
    // reference family maps directly so coprocessors can mutate them if desired
    Map<byte[],List<KeyValue>>[] familyMaps = new Map[batchOp.operations.length];
    // We try to set up a batch in the range [firstIndex,lastIndexExclusive)
    int firstIndex = batchOp.nextIndexToProcess;
    int lastIndexExclusive = firstIndex;
    boolean success = false;
    int noOfPuts = 0, noOfDeletes = 0;
    try {
      // ------------------------------------
      // STEP 1. Try to acquire as many locks as we can, and ensure
      // we acquire at least one.
      // ----------------------------------
      int numReadyToWrite = 0;
      long now = EnvironmentEdgeManager.currentTimeMillis();
      while (lastIndexExclusive < batchOp.operations.length) {
        Pair<Mutation, Integer> nextPair = batchOp.operations[lastIndexExclusive];
        Mutation mutation = nextPair.getFirst();
        boolean isPutMutation = mutation instanceof Put;
        Integer providedLockId = nextPair.getSecond();

        Map<byte[], List<KeyValue>> familyMap = mutation.getFamilyMap();
        // store the family map reference to allow for mutations
        familyMaps[lastIndexExclusive] = familyMap;

        // skip anything that "ran" already
        if (batchOp.retCodeDetails[lastIndexExclusive].getOperationStatusCode()
            != OperationStatusCode.NOT_RUN) {
          lastIndexExclusive++;
          continue;
        }

        try {
          if (isPutMutation) {
            // Check the families in the put. If bad, skip this one.
            checkFamilies(familyMap.keySet());
            checkTimestamps(mutation.getFamilyMap(), now);
          } else {
            prepareDelete((Delete) mutation);
          }
        } catch (NoSuchColumnFamilyException nscf) {
          LOG.warn("No such column family in batch mutation", nscf);
          batchOp.retCodeDetails[lastIndexExclusive] = new OperationStatus(
              OperationStatusCode.BAD_FAMILY, nscf.getMessage());
          lastIndexExclusive++;
          continue;
        } catch (FailedSanityCheckException fsce) {
          LOG.warn("Batch Mutation did not pass sanity check", fsce);
          batchOp.retCodeDetails[lastIndexExclusive] = new OperationStatus(
              OperationStatusCode.SANITY_CHECK_FAILURE, fsce.getMessage());
          lastIndexExclusive++;
          continue;
        }
        // If we haven't got any rows in our batch, we should block to
        // get the next one.
        boolean shouldBlock = numReadyToWrite == 0;
        Integer acquiredLockId = null;
        try {
          acquiredLockId = getLock(providedLockId, mutation.getRow(),
              shouldBlock);
        } catch (IOException ioe) {
          LOG.warn("Failed getting lock in batch put, row="
                  + Bytes.toStringBinary(mutation.getRow()), ioe);
        }
        if (acquiredLockId == null) {
          // We failed to grab another lock
          assert !shouldBlock : "Should never fail to get lock when blocking";
          break; // stop acquiring more rows for this batch
        }
        if (providedLockId == null) {
          acquiredLocks.add(acquiredLockId);
        }
        lastIndexExclusive++;
        numReadyToWrite++;

        if (isPutMutation) {
          // If Column Families stay consistent through out all of the
          // individual puts then metrics can be reported as a mutliput across
          // column families in the first put.
          if (putsCfSet == null) {
            putsCfSet = mutation.getFamilyMap().keySet();
          } else {
            putsCfSetConsistent = putsCfSetConsistent
                && mutation.getFamilyMap().keySet().equals(putsCfSet);
          }
        } else {
          if (deletesCfSet == null) {
            deletesCfSet = mutation.getFamilyMap().keySet();
          } else {
            deletesCfSetConsistent = deletesCfSetConsistent
                && mutation.getFamilyMap().keySet().equals(deletesCfSet);
          }
        }
      }

      // we should record the timestamp only after we have acquired the rowLock,
      // otherwise, newer puts/deletes are not guaranteed to have a newer timestamp
      now = EnvironmentEdgeManager.currentTimeMillis();
      byte[] byteNow = Bytes.toBytes(now);

      // Nothing to put/delete -- an exception in the above such as NoSuchColumnFamily?
      if (numReadyToWrite <= 0) return 0L;

      // We've now grabbed as many mutations off the list as we can

      // ------------------------------------
      // STEP 2. Update any LATEST_TIMESTAMP timestamps
      // ----------------------------------
      for (int i = firstIndex; i < lastIndexExclusive; i++) {
        // skip invalid
        if (batchOp.retCodeDetails[i].getOperationStatusCode()
            != OperationStatusCode.NOT_RUN) continue;

        Mutation mutation = batchOp.operations[i].getFirst();
        if (mutation instanceof Put) {
          updateKVTimestamps(familyMaps[i].values(), byteNow);
          noOfPuts++;
        } else {
          prepareDeleteTimestamps(familyMaps[i], byteNow);
          noOfDeletes++;
        }
      }

      this.updatesLock.readLock().lock();
      locked = true;

      //
      // ------------------------------------
      // Acquire the latest mvcc number
      // ----------------------------------
      w = mvcc.beginMemstoreInsert();

      // ------------------------------------
      // STEP 3. Write back to memstore
      // Write to memstore. It is ok to write to memstore
      // first without updating the HLog because we do not roll
      // forward the memstore MVCC. The MVCC will be moved up when
      // the complete operation is done. These changes are not yet
      // visible to scanners till we update the MVCC. The MVCC is
      // moved only when the sync is complete.
      // ----------------------------------
      long addedSize = 0;
      for (int i = firstIndex; i < lastIndexExclusive; i++) {
        if (batchOp.retCodeDetails[i].getOperationStatusCode()
            != OperationStatusCode.NOT_RUN) {
          continue;
        }
        addedSize += applyFamilyMapToMemstore(familyMaps[i], w);
      }

      // ------------------------------------
      // STEP 4. Build WAL edit
      // ----------------------------------
      for (int i = firstIndex; i < lastIndexExclusive; i++) {
        // Skip puts that were determined to be invalid during preprocessing
        if (batchOp.retCodeDetails[i].getOperationStatusCode()
            != OperationStatusCode.NOT_RUN) {
          continue;
        }
        batchOp.retCodeDetails[i] = OperationStatus.SUCCESS;

        Mutation m = batchOp.operations[i].getFirst();
        if (!m.getWriteToWAL()) {
          if (m instanceof Put) {
            recordPutWithoutWal(m.getFamilyMap());
          }
          continue;
        }
        // Add WAL edits by CP
        WALEdit fromCP = batchOp.walEditsFromCoprocessors[i];
        if (fromCP != null) {
          for (KeyValue kv : fromCP.getKeyValues()) {
            walEdit.add(kv);
          }
        }
        addFamilyMapToWALEdit(familyMaps[i], walEdit);

      }

      // -------------------------
      // STEP 5. Append the edit to WAL. Do not sync wal.
      // -------------------------
      Mutation first = batchOp.operations[firstIndex].getFirst();
      txid = this.log.appendNoSync(regionInfo, this.htableDescriptor.getName(),
               walEdit, first.getClusterId(), now, this.htableDescriptor);

      // -------------------------------
      // STEP 6. Release row locks, etc.
      // -------------------------------
      if (locked) {
        this.updatesLock.readLock().unlock();
        locked = false;
      }
      if (acquiredLocks != null) {
        for (Integer toRelease : acquiredLocks) {
          releaseRowLock(toRelease);
        }
        acquiredLocks = null;
      }
      // -------------------------
      // STEP 7. Sync wal.
      // -------------------------
      if (walEdit.size() > 0) {
        syncOrDefer(txid);
      }
      walSyncSuccessful = true;
      // ------------------------------------------------------------------
      // STEP 8. Advance mvcc. This will make this put visible to scanners and getters.
      // ------------------------------------------------------------------
      if (w != null) {
        mvcc.completeMemstoreInsert(w);
        w = null;
      }

      // ------------------------------------
      // STEP 9. Run coprocessor post hooks. This should be done after the wal is
      // synced so that the coprocessor contract is adhered to.
      // ------------------------------------
      if (coprocessorHost != null) {
        for (int i = firstIndex; i < lastIndexExclusive; i++) {
          // only for successful puts
          if (batchOp.retCodeDetails[i].getOperationStatusCode()
              != OperationStatusCode.SUCCESS) {
            continue;
          }
          Mutation m = batchOp.operations[i].getFirst();
          if (m instanceof Put) {
            coprocessorHost.postPut((Put) m, walEdit, m.getWriteToWAL());
          } else {
            coprocessorHost.postDelete((Delete) m, walEdit, m.getWriteToWAL());
          }
        }
      }

      success = true;
      return addedSize;
    } finally {

      // if the wal sync was unsuccessful, remove keys from memstore
      if (!walSyncSuccessful) {
        rollbackMemstore(batchOp, familyMaps, firstIndex, lastIndexExclusive);
      }
      if (w != null) mvcc.completeMemstoreInsert(w);

      if (locked) {
        this.updatesLock.readLock().unlock();
      }

      if (acquiredLocks != null) {
        for (Integer toRelease : acquiredLocks) {
          releaseRowLock(toRelease);
        }
      }

      // See if the column families were consistent through the whole thing.
      // if they were then keep them. If they were not then pass a null.
      // null will be treated as unknown.
      // Total time taken might be involving Puts and Deletes.
      // Split the time for puts and deletes based on the total number of Puts and Deletes.

      if (noOfPuts > 0) {
        // There were some Puts in the batch.
        double noOfMutations = noOfPuts + noOfDeletes;
        this.metricsRegion.updatePut();
      }
      if (noOfDeletes > 0) {
        // There were some Deletes in the batch.
        this.metricsRegion.updateDelete();
      }
      if (!success) {
        for (int i = firstIndex; i < lastIndexExclusive; i++) {
          if (batchOp.retCodeDetails[i].getOperationStatusCode() == OperationStatusCode.NOT_RUN) {
            batchOp.retCodeDetails[i] = OperationStatus.FAILURE;
          }
        }
      }
      batchOp.nextIndexToProcess = lastIndexExclusive;
    }
  }

  //TODO, Think that gets/puts and deletes should be refactored a bit so that
  //the getting of the lock happens before, so that you would just pass it into
  //the methods. So in the case of checkAndMutate you could just do lockRow,
  //get, put, unlockRow or something
  /**
   *
   * @param row
   * @param family
   * @param qualifier
   * @param compareOp
   * @param comparator
   * @param lockId
   * @param writeToWAL
   * @throws IOException
   * @return true if the new put was executed, false otherwise
   */
  public boolean checkAndMutate(byte [] row, byte [] family, byte [] qualifier,
      CompareOp compareOp, ByteArrayComparable comparator, Writable w,
      Integer lockId, boolean writeToWAL)
  throws IOException{
    checkReadOnly();
    //TODO, add check for value length or maybe even better move this to the
    //client if this becomes a global setting
    checkResources();
    boolean isPut = w instanceof Put;
    if (!isPut && !(w instanceof Delete))
      throw new DoNotRetryIOException("Action must be Put or Delete");
    Row r = (Row)w;
    if (!Bytes.equals(row, r.getRow())) {
      throw new DoNotRetryIOException("Action's getRow must match the passed row");
    }

    startRegionOperation();
    try {
      RowLock lock = isPut ? ((Put)w).getRowLock() : ((Delete)w).getRowLock();
      Get get = new Get(row, lock);
      checkFamily(family);
      get.addColumn(family, qualifier);

      // Lock row
      Integer lid = getLock(lockId, get.getRow(), true);
      // wait for all previous transactions to complete (with lock held)
      mvcc.completeMemstoreInsert(mvcc.beginMemstoreInsert());
      List<KeyValue> result = null;
      try {
        result = get(get, false);

        boolean valueIsNull = comparator.getValue() == null ||
          comparator.getValue().length == 0;
        boolean matches = false;
        if (result.size() == 0 && valueIsNull) {
          matches = true;
        } else if (result.size() > 0 && result.get(0).getValue().length == 0 &&
            valueIsNull) {
          matches = true;
        } else if (result.size() == 1 && !valueIsNull) {
          KeyValue kv = result.get(0);
          int compareResult = comparator.compareTo(kv.getBuffer(),
              kv.getValueOffset(), kv.getValueLength());
          switch (compareOp) {
          case LESS:
            matches = compareResult <= 0;
            break;
          case LESS_OR_EQUAL:
            matches = compareResult < 0;
            break;
          case EQUAL:
            matches = compareResult == 0;
            break;
          case NOT_EQUAL:
            matches = compareResult != 0;
            break;
          case GREATER_OR_EQUAL:
            matches = compareResult > 0;
            break;
          case GREATER:
            matches = compareResult >= 0;
            break;
          default:
            throw new RuntimeException("Unknown Compare op " + compareOp.name());
          }
        }
        //If matches put the new put or delete the new delete
        if (matches) {
          // All edits for the given row (across all column families) must
          // happen atomically.
          doBatchMutate((Mutation)w, lid);
          this.checkAndMutateChecksPassed.increment();
          return true;
        }
        this.checkAndMutateChecksFailed.increment();
        return false;
      } finally {
        if(lockId == null) releaseRowLock(lid);
      }
    } finally {
      closeRegionOperation();
    }
  }

  @SuppressWarnings("unchecked")
  private void doBatchMutate(Mutation mutation, Integer lid) throws IOException,
      DoNotRetryIOException {
    Pair<Mutation, Integer>[] mutateWithLocks = new Pair[] { new Pair<Mutation, Integer>(mutation,
        lid) };
    OperationStatus[] batchMutate = this.batchMutate(mutateWithLocks);
    if (batchMutate[0].getOperationStatusCode().equals(OperationStatusCode.SANITY_CHECK_FAILURE)) {
      throw new FailedSanityCheckException(batchMutate[0].getExceptionMsg());
    } else if (batchMutate[0].getOperationStatusCode().equals(OperationStatusCode.BAD_FAMILY)) {
      throw new NoSuchColumnFamilyException(batchMutate[0].getExceptionMsg());
    }
  }

  /**
   * Replaces any KV timestamps set to {@link HConstants#LATEST_TIMESTAMP}
   * with the provided current timestamp.
   */
  void updateKVTimestamps(
      final Iterable<List<KeyValue>> keyLists, final byte[] now) {
    for (List<KeyValue> keys: keyLists) {
      if (keys == null) continue;
      for (KeyValue key : keys) {
        key.updateLatestStamp(now);
      }
    }
  }

  /*
   * Check if resources to support an update.
   *
   * Here we synchronize on HRegion, a broad scoped lock.  Its appropriate
   * given we're figuring in here whether this region is able to take on
   * writes.  This is only method with a synchronize (at time of writing),
   * this and the synchronize on 'this' inside in internalFlushCache to send
   * the notify.
   */
  private void checkResources() {

    // If catalog region, do not impose resource constraints or block updates.
    if (this.getRegionInfo().isMetaRegion()) return;

    boolean blocked = false;
    long startTime = 0;
    while (this.memstoreSize.get() > this.blockingMemStoreSize) {
      requestFlush();
      if (!blocked) {
        startTime = EnvironmentEdgeManager.currentTimeMillis();
        LOG.info("Blocking updates for '" + Thread.currentThread().getName() +
          "' on region " + Bytes.toStringBinary(getRegionName()) +
          ": memstore size " +
          StringUtils.humanReadableInt(this.memstoreSize.get()) +
          " is >= than blocking " +
          StringUtils.humanReadableInt(this.blockingMemStoreSize) + " size");
      }
      blocked = true;
      synchronized(this) {
        try {
          wait(threadWakeFrequency);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    if (blocked) {
      // Add in the blocked time if appropriate
      final long totalTime = EnvironmentEdgeManager.currentTimeMillis() - startTime;
      if(totalTime > 0 ){
        this.updatesBlockedMs.add(totalTime);
      }
      LOG.info("Unblocking updates for region " + this + " '"
          + Thread.currentThread().getName() + "'");
    }
  }

  /**
   * @throws IOException Throws exception if region is in read-only mode.
   */
  protected void checkReadOnly() throws IOException {
    if (this.writestate.isReadOnly()) {
      throw new IOException("region is read only");
    }
  }

  /**
   * Add updates first to the hlog and then add values to memstore.
   * Warning: Assumption is caller has lock on passed in row.
   * @param family
   * @param edits Cell updates by column
   * @praram now
   * @throws IOException
   */
  private void put(byte [] family, List<KeyValue> edits, Integer lid)
  throws IOException {
    Map<byte[], List<KeyValue>> familyMap;
    familyMap = new HashMap<byte[], List<KeyValue>>();

    familyMap.put(family, edits);
    Put p = new Put();
    p.setFamilyMap(familyMap);
    p.setClusterId(HConstants.DEFAULT_CLUSTER_ID);
    p.setWriteToWAL(true);
    doBatchMutate(p, lid);
  }
 
  /**
   * Atomically apply the given map of family->edits to the memstore.
   * This handles the consistency control on its own, but the caller
   * should already have locked updatesLock.readLock(). This also does
   * <b>not</b> check the families for validity.
   *
   * @param familyMap Map of kvs per family
   * @param localizedWriteEntry The WriteEntry of the MVCC for this transaction.
   *        If null, then this method internally creates a mvcc transaction.
   * @return the additional memory usage of the memstore caused by the
   * new entries.
   */
  private long applyFamilyMapToMemstore(Map<byte[], List<KeyValue>> familyMap,
    MultiVersionConsistencyControl.WriteEntry localizedWriteEntry) {
    long size = 0;
    boolean freemvcc = false;

    try {
      if (localizedWriteEntry == null) {
        localizedWriteEntry = mvcc.beginMemstoreInsert();
        freemvcc = true;
      }

      for (Map.Entry<byte[], List<KeyValue>> e : familyMap.entrySet()) {
        byte[] family = e.getKey();
        List<KeyValue> edits = e.getValue();

        Store store = getStore(family);
        for (KeyValue kv: edits) {
          kv.setMemstoreTS(localizedWriteEntry.getWriteNumber());
          size += store.add(kv);
        }
      }
    } finally {
      if (freemvcc) {
        mvcc.completeMemstoreInsert(localizedWriteEntry);
      }
    }

     return size;
   }

  /**
   * Remove all the keys listed in the map from the memstore. This method is
   * called when a Put/Delete has updated memstore but subequently fails to update
   * the wal. This method is then invoked to rollback the memstore.
   */
  private void rollbackMemstore(BatchOperationInProgress<Pair<Mutation, Integer>> batchOp,
                                Map<byte[], List<KeyValue>>[] familyMaps,
                                int start, int end) {
    int kvsRolledback = 0;
    for (int i = start; i < end; i++) {
      // skip over request that never succeeded in the first place.
      if (batchOp.retCodeDetails[i].getOperationStatusCode()
            != OperationStatusCode.SUCCESS) {
        continue;
      }

      // Rollback all the kvs for this row.
      Map<byte[], List<KeyValue>> familyMap  = familyMaps[i];
      for (Map.Entry<byte[], List<KeyValue>> e : familyMap.entrySet()) {
        byte[] family = e.getKey();
        List<KeyValue> edits = e.getValue();

        // Remove those keys from the memstore that matches our
        // key's (row, cf, cq, timestamp, memstoreTS). The interesting part is
        // that even the memstoreTS has to match for keys that will be rolleded-back.
        Store store = getStore(family);
        for (KeyValue kv: edits) {
          store.rollback(kv);
          kvsRolledback++;
        }
      }
    }
    LOG.debug("rollbackMemstore rolled back " + kvsRolledback +
        " keyvalues from start:" + start + " to end:" + end);
  }

  /**
   * Check the collection of families for validity.
   * @throws NoSuchColumnFamilyException if a family does not exist.
   */
  void checkFamilies(Collection<byte[]> families)
  throws NoSuchColumnFamilyException {
    for (byte[] family : families) {
      checkFamily(family);
    }
  }

  void checkTimestamps(final Map<byte[], List<KeyValue>> familyMap,
      long now) throws FailedSanityCheckException {
    if (timestampSlop == HConstants.LATEST_TIMESTAMP) {
      return;
    }
    long maxTs = now + timestampSlop;
    for (List<KeyValue> kvs : familyMap.values()) {
      for (KeyValue kv : kvs) {
        // see if the user-side TS is out of range. latest = server-side
        if (!kv.isLatestTimestamp() && kv.getTimestamp() > maxTs) {
          throw new FailedSanityCheckException("Timestamp for KV out of range "
              + kv + " (too.new=" + timestampSlop + ")");
        }
      }
    }
  }

  /**
   * Append the given map of family->edits to a WALEdit data structure.
   * This does not write to the HLog itself.
   * @param familyMap map of family->edits
   * @param walEdit the destination entry to append into
   */
  private void addFamilyMapToWALEdit(Map<byte[], List<KeyValue>> familyMap,
      WALEdit walEdit) {
    for (List<KeyValue> edits : familyMap.values()) {
      for (KeyValue kv : edits) {
        walEdit.add(kv);
      }
    }
  }

  private void requestFlush() {
    if (this.rsServices == null) {
      return;
    }
    synchronized (writestate) {
      if (this.writestate.isFlushRequested()) {
        return;
      }
      writestate.flushRequested = true;
    }
    // Make request outside of synchronize block; HBASE-818.
    this.rsServices.getFlushRequester().requestFlush(this);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Flush requested on " + this);
    }
  }

  /*
   * @param size
   * @return True if size is over the flush threshold
   */
  private boolean isFlushSize(final long size) {
    return size > this.memstoreFlushSize;
  }

  /**
   * Read the edits log put under this region by wal log splitting process.  Put
   * the recovered edits back up into this region.
   *
   * <p>We can ignore any log message that has a sequence ID that's equal to or
   * lower than minSeqId.  (Because we know such log messages are already
   * reflected in the HFiles.)
   *
   * <p>While this is running we are putting pressure on memory yet we are
   * outside of our usual accounting because we are not yet an onlined region
   * (this stuff is being run as part of Region initialization).  This means
   * that if we're up against global memory limits, we'll not be flagged to flush
   * because we are not online. We can't be flushed by usual mechanisms anyways;
   * we're not yet online so our relative sequenceids are not yet aligned with
   * HLog sequenceids -- not till we come up online, post processing of split
   * edits.
   *
   * <p>But to help relieve memory pressure, at least manage our own heap size
   * flushing if are in excess of per-region limits.  Flushing, though, we have
   * to be careful and avoid using the regionserver/hlog sequenceid.  Its running
   * on a different line to whats going on in here in this region context so if we
   * crashed replaying these edits, but in the midst had a flush that used the
   * regionserver log with a sequenceid in excess of whats going on in here
   * in this region and with its split editlogs, then we could miss edits the
   * next time we go to recover. So, we have to flush inline, using seqids that
   * make sense in a this single region context only -- until we online.
   *
   * @param regiondir
   * @param maxSeqIdInStores Any edit found in split editlogs needs to be in excess of
   * the maxSeqId for the store to be applied, else its skipped.
   * @param reporter
   * @return the sequence id of the last edit added to this region out of the
   * recovered edits log or <code>minSeqId</code> if nothing added from editlogs.
   * @throws UnsupportedEncodingException
   * @throws IOException
   */
  protected long replayRecoveredEditsIfAny(final Path regiondir,
      Map<byte[], Long> maxSeqIdInStores,
      final CancelableProgressable reporter, final MonitoredTask status)
      throws UnsupportedEncodingException, IOException {
    long minSeqIdForTheRegion = -1;
    for (Long maxSeqIdInStore : maxSeqIdInStores.values()) {
      if (maxSeqIdInStore < minSeqIdForTheRegion || minSeqIdForTheRegion == -1) {
        minSeqIdForTheRegion = maxSeqIdInStore;
      }
    }
    long seqid = minSeqIdForTheRegion;
    
    NavigableSet<Path> files = HLogUtil.getSplitEditFilesSorted(fs, regiondir);
    if (files == null || files.isEmpty()) return seqid;

    for (Path edits: files) {
      if (edits == null || !this.fs.exists(edits)) {
        LOG.warn("Null or non-existent edits file: " + edits);
        continue;
      }
      if (isZeroLengthThenDelete(this.fs, edits)) continue;

      long maxSeqId = Long.MAX_VALUE;
      String fileName = edits.getName();
      maxSeqId = Math.abs(Long.parseLong(fileName));
      if (maxSeqId <= minSeqIdForTheRegion) {
        String msg = "Maximum sequenceid for this log is " + maxSeqId
            + " and minimum sequenceid for the region is " + minSeqIdForTheRegion
            + ", skipped the whole file, path=" + edits;
        LOG.debug(msg);
        continue;
      }

      try {
        seqid = replayRecoveredEdits(edits, maxSeqIdInStores, reporter);
      } catch (IOException e) {
        boolean skipErrors = conf.getBoolean(
            HConstants.HREGION_EDITS_REPLAY_SKIP_ERRORS,
            conf.getBoolean(
                "hbase.skip.errors",
                HConstants.DEFAULT_HREGION_EDITS_REPLAY_SKIP_ERRORS));
        if (conf.get("hbase.skip.errors") != null) {
          LOG.warn(
              "The property 'hbase.skip.errors' has been deprecated. Please use " +
              HConstants.HREGION_EDITS_REPLAY_SKIP_ERRORS + " instead.");
        }
        if (skipErrors) {
          Path p = HLogUtil.moveAsideBadEditsFile(fs, edits);
          LOG.error(HConstants.HREGION_EDITS_REPLAY_SKIP_ERRORS
              + "=true so continuing. Renamed " + edits +
              " as " + p, e);
        } else {
          throw e;
        }
      }
      // The edits size added into rsAccounting during this replaying will not
      // be required any more. So just clear it.
      if (this.rsAccounting != null) {
        this.rsAccounting.clearRegionReplayEditsSize(this.regionInfo.getRegionName());
      }
    }
    if (seqid > minSeqIdForTheRegion) {
      // Then we added some edits to memory. Flush and cleanup split edit files.
      internalFlushcache(null, seqid, status);
    }
    // Now delete the content of recovered edits.  We're done w/ them.
    for (Path file: files) {
      if (!this.fs.delete(file, false)) {
        LOG.error("Failed delete of " + file);
      } else {
        LOG.debug("Deleted recovered.edits file=" + file);
      }
    }
    return seqid;
  }

  /*
   * @param edits File of recovered edits.
   * @param maxSeqIdInStores Maximum sequenceid found in each store.  Edits in log
   * must be larger than this to be replayed for each store.
   * @param reporter
   * @return the sequence id of the last edit added to this region out of the
   * recovered edits log or <code>minSeqId</code> if nothing added from editlogs.
   * @throws IOException
   */
  private long replayRecoveredEdits(final Path edits,
      Map<byte[], Long> maxSeqIdInStores, final CancelableProgressable reporter)
    throws IOException {
    String msg = "Replaying edits from " + edits;
    LOG.info(msg);
    MonitoredTask status = TaskMonitor.get().createStatus(msg);

    status.setStatus("Opening logs");
    HLog.Reader reader = null;
    try {
      reader = HLogFactory.createReader(this.fs, edits, conf);
      long currentEditSeqId = -1;
      long firstSeqIdInLog = -1;
      long skippedEdits = 0;
      long editsCount = 0;
      long intervalEdits = 0;
      HLog.Entry entry;
      Store store = null;
      boolean reported_once = false;

      try {
        // How many edits seen before we check elapsed time
        int interval = this.conf.getInt("hbase.hstore.report.interval.edits",
            2000);
        // How often to send a progress report (default 1/2 master timeout)
        int period = this.conf.getInt("hbase.hstore.report.period",
            this.conf.getInt("hbase.master.assignment.timeoutmonitor.timeout",
                180000) / 2);
        long lastReport = EnvironmentEdgeManager.currentTimeMillis();

        while ((entry = reader.next()) != null) {
          HLogKey key = entry.getKey();
          WALEdit val = entry.getEdit();

          if (reporter != null) {
            intervalEdits += val.size();
            if (intervalEdits >= interval) {
              // Number of edits interval reached
              intervalEdits = 0;
              long cur = EnvironmentEdgeManager.currentTimeMillis();
              if (lastReport + period <= cur) {
                status.setStatus("Replaying edits..." +
                    " skipped=" + skippedEdits +
                    " edits=" + editsCount);
                // Timeout reached
                if(!reporter.progress()) {
                  msg = "Progressable reporter failed, stopping replay";
                  LOG.warn(msg);
                  status.abort(msg);
                  throw new IOException(msg);
                }
                reported_once = true;
                lastReport = cur;
              }
            }
          }

          // Start coprocessor replay here. The coprocessor is for each WALEdit
          // instead of a KeyValue.
          if (coprocessorHost != null) {
            status.setStatus("Running pre-WAL-restore hook in coprocessors");
            if (coprocessorHost.preWALRestore(this.getRegionInfo(), key, val)) {
              // if bypass this log entry, ignore it ...
              continue;
            }
          }

          if (firstSeqIdInLog == -1) {
            firstSeqIdInLog = key.getLogSeqNum();
          }
          boolean flush = false;
          for (KeyValue kv: val.getKeyValues()) {
            // Check this edit is for me. Also, guard against writing the special
            // METACOLUMN info such as HBASE::CACHEFLUSH entries
            if (kv.matchingFamily(HLog.METAFAMILY) ||
                !Bytes.equals(key.getEncodedRegionName(), this.regionInfo.getEncodedNameAsBytes())) {
              skippedEdits++;
              continue;
                }
            // Figure which store the edit is meant for.
            if (store == null || !kv.matchingFamily(store.getFamily().getName())) {
              store = this.stores.get(kv.getFamily());
            }
            if (store == null) {
              // This should never happen.  Perhaps schema was changed between
              // crash and redeploy?
              LOG.warn("No family for " + kv);
              skippedEdits++;
              continue;
            }
            // Now, figure if we should skip this edit.
            if (key.getLogSeqNum() <= maxSeqIdInStores.get(store.getFamily()
                .getName())) {
              skippedEdits++;
              continue;
            }
            currentEditSeqId = key.getLogSeqNum();
            // Once we are over the limit, restoreEdit will keep returning true to
            // flush -- but don't flush until we've played all the kvs that make up
            // the WALEdit.
            flush = restoreEdit(store, kv);
            editsCount++;
          }
          if (flush) internalFlushcache(null, currentEditSeqId, status);

          if (coprocessorHost != null) {
            coprocessorHost.postWALRestore(this.getRegionInfo(), key, val);
          }
        }
      } catch (EOFException eof) {
        Path p = HLogUtil.moveAsideBadEditsFile(fs, edits);
        msg = "Encountered EOF. Most likely due to Master failure during " +
            "log spliting, so we have this data in another edit.  " +
            "Continuing, but renaming " + edits + " as " + p;
        LOG.warn(msg, eof);
        status.abort(msg);
      } catch (IOException ioe) {
        // If the IOE resulted from bad file format,
        // then this problem is idempotent and retrying won't help
        if (ioe.getCause() instanceof ParseException) {
          Path p = HLogUtil.moveAsideBadEditsFile(fs, edits);
          msg = "File corruption encountered!  " +
              "Continuing, but renaming " + edits + " as " + p;
          LOG.warn(msg, ioe);
          status.setStatus(msg);
        } else {
          status.abort(StringUtils.stringifyException(ioe));
          // other IO errors may be transient (bad network connection,
          // checksum exception on one datanode, etc).  throw & retry
          throw ioe;
        }
      }
      if (reporter != null && !reported_once) {
        reporter.progress();
      }
      msg = "Applied " + editsCount + ", skipped " + skippedEdits +
        ", firstSequenceidInLog=" + firstSeqIdInLog +
        ", maxSequenceidInLog=" + currentEditSeqId + ", path=" + edits;
      status.markComplete(msg);
      LOG.debug(msg);
      return currentEditSeqId;
    } finally {
      status.cleanup();
      if (reader != null) {
         reader.close();
      }
    }
  }

  /**
   * Used by tests
   * @param s Store to add edit too.
   * @param kv KeyValue to add.
   * @return True if we should flush.
   */
  protected boolean restoreEdit(final Store s, final KeyValue kv) {
    long kvSize = s.add(kv);
    if (this.rsAccounting != null) {
      rsAccounting.addAndGetRegionReplayEditsSize(this.regionInfo.getRegionName(), kvSize);
    }
    return isFlushSize(this.addAndGetGlobalMemstoreSize(kvSize));
  }

  /*
   * @param fs
   * @param p File to check.
   * @return True if file was zero-length (and if so, we'll delete it in here).
   * @throws IOException
   */
  private static boolean isZeroLengthThenDelete(final FileSystem fs, final Path p)
      throws IOException {
    FileStatus stat = fs.getFileStatus(p);
    if (stat.getLen() > 0) return false;
    LOG.warn("File " + p + " is zero-length, deleting.");
    fs.delete(p, false);
    return true;
  }

  protected HStore instantiateHStore(Path tableDir, HColumnDescriptor c)
      throws IOException {
    return new HStore(tableDir, this, c, this.fs, this.conf);
  }

  /**
   * Return HStore instance.
   * Use with caution.  Exposed for use of fixup utilities.
   * @param column Name of column family hosted by this region.
   * @return Store that goes with the family on passed <code>column</code>.
   * TODO: Make this lookup faster.
   */
  public Store getStore(final byte[] column) {
    return this.stores.get(column);
  }

  public Map<byte[], Store> getStores() {
    return this.stores;
  }

  /**
   * Return list of storeFiles for the set of CFs.
   * Uses closeLock to prevent the race condition where a region closes
   * in between the for loop - closing the stores one by one, some stores
   * will return 0 files.
   * @return List of storeFiles.
   */
  public List<String> getStoreFileList(final byte [][] columns)
    throws IllegalArgumentException {
    List<String> storeFileNames = new ArrayList<String>();
    synchronized(closeLock) {
      for(byte[] column : columns) {
        Store store = this.stores.get(column);
        if (store == null) {
          throw new IllegalArgumentException("No column family : " +
              new String(column) + " available");
        }
        List<StoreFile> storeFiles = store.getStorefiles();
        for (StoreFile storeFile: storeFiles) {
          storeFileNames.add(storeFile.getPath().toString());
        }
      }
    }
    return storeFileNames;
  }
  //////////////////////////////////////////////////////////////////////////////
  // Support code
  //////////////////////////////////////////////////////////////////////////////

  /** Make sure this is a valid row for the HRegion */
  void checkRow(final byte [] row, String op) throws IOException {
    if(!rowIsInRange(regionInfo, row)) {
      throw new WrongRegionException("Requested row out of range for " +
          op + " on HRegion " + this + ", startKey='" +
          Bytes.toStringBinary(regionInfo.getStartKey()) + "', getEndKey()='" +
          Bytes.toStringBinary(regionInfo.getEndKey()) + "', row='" +
          Bytes.toStringBinary(row) + "'");
    }
  }

  /**
   * Obtain a lock on the given row.  Blocks until success.
   *
   * I know it's strange to have two mappings:
   * <pre>
   *   ROWS  ==> LOCKS
   * </pre>
   * as well as
   * <pre>
   *   LOCKS ==> ROWS
   * </pre>
   *
   * But it acts as a guard on the client; a miswritten client just can't
   * submit the name of a row and start writing to it; it must know the correct
   * lockid, which matches the lock list in memory.
   *
   * <p>It would be more memory-efficient to assume a correctly-written client,
   * which maybe we'll do in the future.
   *
   * @param row Name of row to lock.
   * @throws IOException
   * @return The id of the held lock.
   */
  public Integer obtainRowLock(final byte [] row) throws IOException {
    startRegionOperation();
    this.writeRequestsCount.increment();
    try {
      return internalObtainRowLock(row, true);
    } finally {
      closeRegionOperation();
    }
  }

  /**
   * Obtains or tries to obtain the given row lock.
   * @param waitForLock if true, will block until the lock is available.
   *        Otherwise, just tries to obtain the lock and returns
   *        null if unavailable.
   */
  private Integer internalObtainRowLock(final byte[] row, boolean waitForLock)
      throws IOException {
    checkRow(row, "row lock");
    startRegionOperation();
    try {
      HashedBytes rowKey = new HashedBytes(row);
      CountDownLatch rowLatch = new CountDownLatch(1);

      // loop until we acquire the row lock (unless !waitForLock)
      while (true) {
        CountDownLatch existingLatch = lockedRows.putIfAbsent(rowKey, rowLatch);
        if (existingLatch == null) {
          break;
        } else {
          // row already locked
          if (!waitForLock) {
            return null;
          }
          try {
            if (!existingLatch.await(this.rowLockWaitDuration,
                            TimeUnit.MILLISECONDS)) {
              throw new IOException("Timed out on getting lock for row="
                  + Bytes.toStringBinary(row));
            }
          } catch (InterruptedException ie) {
            // Empty
          }
        }
      }

      // loop until we generate an unused lock id
      while (true) {
        Integer lockId = lockIdGenerator.incrementAndGet();
        HashedBytes existingRowKey = lockIds.putIfAbsent(lockId, rowKey);
        if (existingRowKey == null) {
          return lockId;
        } else {
          // lockId already in use, jump generator to a new spot
          lockIdGenerator.set(rand.nextInt());
        }
      }
    } finally {
      closeRegionOperation();
    }
  }

  /**
   * Used by unit tests.
   * @param lockid
   * @return Row that goes with <code>lockid</code>
   */
  byte[] getRowFromLock(final Integer lockid) {
    HashedBytes rowKey = lockIds.get(lockid);
    return rowKey == null ? null : rowKey.getBytes();
  }

  /**
   * Release the row lock!
   * @param lockId  The lock ID to release.
   */
  public void releaseRowLock(final Integer lockId) {
    HashedBytes rowKey = lockIds.remove(lockId);
    if (rowKey == null) {
      LOG.warn("Release unknown lockId: " + lockId);
      return;
    }
    CountDownLatch rowLatch = lockedRows.remove(rowKey);
    if (rowLatch == null) {
      LOG.error("Releases row not locked, lockId: " + lockId + " row: "
          + rowKey);
      return;
    }
    rowLatch.countDown();
  }

  /**
   * See if row is currently locked.
   * @param lockId
   * @return boolean
   */
  boolean isRowLocked(final Integer lockId) {
    return lockIds.containsKey(lockId);
  }

  /**
   * Returns existing row lock if found, otherwise
   * obtains a new row lock and returns it.
   * @param lockid requested by the user, or null if the user didn't already hold lock
   * @param row the row to lock
   * @param waitForLock if true, will block until the lock is available, otherwise will
   * simply return null if it could not acquire the lock.
   * @return lockid or null if waitForLock is false and the lock was unavailable.
   */
  public Integer getLock(Integer lockid, byte [] row, boolean waitForLock)
  throws IOException {
    Integer lid = null;
    if (lockid == null) {
      lid = internalObtainRowLock(row, waitForLock);
    } else {
      if (!isRowLocked(lockid)) {
        throw new IOException("Invalid row lock");
      }
      lid = lockid;
    }
    return lid;
  }

  /**
   * Determines whether multiple column families are present
   * Precondition: familyPaths is not null
   *
   * @param familyPaths List of Pair<byte[] column family, String hfilePath>
   */
  private static boolean hasMultipleColumnFamilies(
      List<Pair<byte[], String>> familyPaths) {
    boolean multipleFamilies = false;
    byte[] family = null;
    for (Pair<byte[], String> pair : familyPaths) {
      byte[] fam = pair.getFirst();
      if (family == null) {
        family = fam;
      } else if (!Bytes.equals(family, fam)) {
        multipleFamilies = true;
        break;
      }
    }
    return multipleFamilies;
  }

  /**
   * Attempts to atomically load a group of hfiles.  This is critical for loading
   * rows with multiple column families atomically.
   *
   * @param familyPaths List of Pair<byte[] column family, String hfilePath>
   * @param assignSeqId
   * @return true if successful, false if failed recoverably
   * @throws IOException if failed unrecoverably.
   */
  public boolean bulkLoadHFiles(List<Pair<byte[], String>> familyPaths,
      boolean assignSeqId) throws IOException {
    Preconditions.checkNotNull(familyPaths);
    // we need writeLock for multi-family bulk load
    startBulkRegionOperation(hasMultipleColumnFamilies(familyPaths));
    try {
      this.writeRequestsCount.increment();

      // There possibly was a split that happend between when the split keys
      // were gathered and before the HReiogn's write lock was taken.  We need
      // to validate the HFile region before attempting to bulk load all of them
      List<IOException> ioes = new ArrayList<IOException>();
      List<Pair<byte[], String>> failures = new ArrayList<Pair<byte[], String>>();
      for (Pair<byte[], String> p : familyPaths) {
        byte[] familyName = p.getFirst();
        String path = p.getSecond();

        Store store = getStore(familyName);
        if (store == null) {
          IOException ioe = new DoNotRetryIOException(
              "No such column family " + Bytes.toStringBinary(familyName));
          ioes.add(ioe);
          failures.add(p);
        } else {
          try {
            store.assertBulkLoadHFileOk(new Path(path));
          } catch (WrongRegionException wre) {
            // recoverable (file doesn't fit in region)
            failures.add(p);
          } catch (IOException ioe) {
            // unrecoverable (hdfs problem)
            ioes.add(ioe);
          }
        }
      }

      // validation failed, bail out before doing anything permanent.
      if (failures.size() != 0) {
        StringBuilder list = new StringBuilder();
        for (Pair<byte[], String> p : failures) {
          list.append("\n").append(Bytes.toString(p.getFirst())).append(" : ")
            .append(p.getSecond());
        }
        // problem when validating
        LOG.warn("There was a recoverable bulk load failure likely due to a" +
            " split.  These (family, HFile) pairs were not loaded: " + list);
        return false;
      }

      // validation failed because of some sort of IO problem.
      if (ioes.size() != 0) {
        IOException e = MultipleIOException.createIOException(ioes);
        LOG.error("There were one or more IO errors when checking if the bulk load is ok.", e);
        throw e;
      }

      for (Pair<byte[], String> p : familyPaths) {
        byte[] familyName = p.getFirst();
        String path = p.getSecond();
        Store store = getStore(familyName);
        try {
          store.bulkLoadHFile(path, assignSeqId ? this.log.obtainSeqNum() : -1);
        } catch (IOException ioe) {
          // A failure here can cause an atomicity violation that we currently
          // cannot recover from since it is likely a failed HDFS operation.

          // TODO Need a better story for reverting partial failures due to HDFS.
          LOG.error("There was a partial failure due to IO when attempting to" +
              " load " + Bytes.toString(p.getFirst()) + " : "+ p.getSecond(), ioe);
          throw ioe;
        }
      }
      return true;
    } finally {
      closeBulkRegionOperation();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof HRegion)) {
      return false;
    }
    return Bytes.equals(this.getRegionName(), ((HRegion) o).getRegionName());
  }

  @Override
  public int hashCode() {
    return Bytes.hashCode(this.getRegionName());
  }

  @Override
  public String toString() {
    return this.regionInfo.getRegionNameAsString();
  }

  /** @return Path of region base directory */
  public Path getTableDir() {
    return this.tableDir;
  }

  /**
   * RegionScannerImpl is used to combine scanners from multiple Stores (aka column families).
   */
  class RegionScannerImpl implements RegionScanner {
    // Package local for testability
    KeyValueHeap storeHeap = null;
    private final byte [] stopRow;
    private Filter filter;
    private List<KeyValue> results = new ArrayList<KeyValue>();
    private int batch;
    private int isScan;
    private boolean filterClosed = false;
    private long readPt;
    private long maxResultSize;

    public HRegionInfo getRegionInfo() {
      return regionInfo;
    }
    RegionScannerImpl(Scan scan, List<KeyValueScanner> additionalScanners) throws IOException {
      //DebugPrint.println("HRegionScanner.<init>");

      this.maxResultSize = scan.getMaxResultSize();
      if (scan.hasFilter()) {
        this.filter = new FilterWrapper(scan.getFilter());
      } else {
        this.filter = null;
      }
      
      this.batch = scan.getBatch();
      if (Bytes.equals(scan.getStopRow(), HConstants.EMPTY_END_ROW)) {
        this.stopRow = null;
      } else {
        this.stopRow = scan.getStopRow();
      }
      // If we are doing a get, we want to be [startRow,endRow] normally
      // it is [startRow,endRow) and if startRow=endRow we get nothing.
      this.isScan = scan.isGetScan() ? -1 : 0;

      // synchronize on scannerReadPoints so that nobody calculates
      // getSmallestReadPoint, before scannerReadPoints is updated.
      IsolationLevel isolationLevel = scan.getIsolationLevel();
      synchronized(scannerReadPoints) {
        if (isolationLevel == IsolationLevel.READ_UNCOMMITTED) {
          // This scan can read even uncommitted transactions
          this.readPt = Long.MAX_VALUE;
          MultiVersionConsistencyControl.setThreadReadPoint(this.readPt);
        } else {
          this.readPt = MultiVersionConsistencyControl.resetThreadReadPoint(mvcc);
        }
        scannerReadPoints.put(this, this.readPt);
      }

      List<KeyValueScanner> scanners = new ArrayList<KeyValueScanner>();
      if (additionalScanners != null) {
        scanners.addAll(additionalScanners);
      }

      for (Map.Entry<byte[], NavigableSet<byte[]>> entry :
          scan.getFamilyMap().entrySet()) {
        Store store = stores.get(entry.getKey());
        KeyValueScanner scanner = store.getScanner(scan, entry.getValue());
        scanners.add(scanner);
      }
      this.storeHeap = new KeyValueHeap(scanners, comparator);
    }

    RegionScannerImpl(Scan scan) throws IOException {
      this(scan, null);
    }

    @Override
    public long getMaxResultSize() {
      return maxResultSize;
    }

    /**
     * Reset both the filter and the old filter.
     */
    protected void resetFilters() {
      if (filter != null) {
        filter.reset();
      }
    }

    @Override
    public synchronized boolean next(List<KeyValue> outResults, int limit)
        throws IOException {
      return next(outResults, limit, null);
    }

    @Override
    public synchronized boolean next(List<KeyValue> outResults, int limit,
        String metric) throws IOException {
      if (this.filterClosed) {
        throw new UnknownScannerException("Scanner was closed (timed out?) " +
            "after we renewed it. Could be caused by a very slow scanner " +
            "or a lengthy garbage collection");
      }
      startRegionOperation();
      readRequestsCount.increment();
      try {

        // This could be a new thread from the last time we called next().
        MultiVersionConsistencyControl.setThreadReadPoint(this.readPt);

        results.clear();

        boolean returnResult = nextInternal(limit, metric);

        outResults.addAll(results);
        resetFilters();
        if (isFilterDone()) {
          return false;
        }
        return returnResult;
      } finally {
        closeRegionOperation();
      }
    }

    @Override
    public synchronized boolean next(List<KeyValue> outResults)
        throws IOException {
      // apply the batching limit by default
      return next(outResults, batch, null);
    }

    @Override
    public synchronized boolean next(List<KeyValue> outResults, String metric)
        throws IOException {
      // apply the batching limit by default
      return next(outResults, batch, metric);
    }

    /*
     * @return True if a filter rules the scanner is over, done.
     */
    public synchronized boolean isFilterDone() {
      return this.filter != null && this.filter.filterAllRemaining();
    }

    private boolean nextInternal(int limit, String metric) throws IOException {
      RpcCallContext rpcCall = HBaseServer.getCurrentCall();
      while (true) {
        if (rpcCall != null) {
          // If a user specifies a too-restrictive or too-slow scanner, the
          // client might time out and disconnect while the server side
          // is still processing the request. We should abort aggressively
          // in that case.
          rpcCall.throwExceptionIfCallerDisconnected();
        }

        byte [] currentRow = peekRow();
        if (isStopRow(currentRow)) {
          if (filter != null && filter.hasFilterRow()) {
            filter.filterRow(results);
          }
          
          return false;
        } else if (filterRowKey(currentRow)) {
          nextRow(currentRow);
        } else {
          byte [] nextRow;
          do {
            this.storeHeap.next(results, limit - results.size(), metric);
            if (limit > 0 && results.size() == limit) {
              if (this.filter != null && filter.hasFilterRow()) {
                throw new IncompatibleFilterException(
                  "Filter whose hasFilterRow() returns true is incompatible with scan with limit!");
              }
              return true; // we are expecting more yes, but also limited to how many we can return.
            }
          } while (Bytes.equals(currentRow, nextRow = peekRow()));

          final boolean stopRow = isStopRow(nextRow);

          // now that we have an entire row, lets process with a filters:

          // first filter with the filterRow(List)
          if (filter != null && filter.hasFilterRow()) {
            filter.filterRow(results);
          }

          if (results.isEmpty()) {
            // this seems like a redundant step - we already consumed the row
            // there're no left overs.
            // the reasons for calling this method are:
            // 1. reset the filters.
            // 2. provide a hook to fast forward the row (used by subclasses)
            nextRow(currentRow);

            // This row was totally filtered out, if this is NOT the last row,
            // we should continue on.

            if (!stopRow) continue;
          }
          return !stopRow;
        }
      }
    }

    private boolean filterRow() {
      return filter != null
          && filter.filterRow();
    }
    private boolean filterRowKey(byte[] row) {
      return filter != null
          && filter.filterRowKey(row, 0, row.length);
    }

    protected void nextRow(byte [] currentRow) throws IOException {
      while (Bytes.equals(currentRow, peekRow())) {
        this.storeHeap.next(MOCKED_LIST);
      }
      results.clear();
      resetFilters();
    }

    private byte[] peekRow() {
      KeyValue kv = this.storeHeap.peek();
      return kv == null ? null : kv.getRow();
    }

    private boolean isStopRow(byte [] currentRow) {
      return currentRow == null ||
          (stopRow != null &&
          comparator.compareRows(stopRow, 0, stopRow.length,
              currentRow, 0, currentRow.length) <= isScan);
    }

    @Override
    public synchronized void close() {
      if (storeHeap != null) {
        storeHeap.close();
        storeHeap = null;
      }
      // no need to sychronize here.
      scannerReadPoints.remove(this);
      this.filterClosed = true;
    }

    KeyValueHeap getStoreHeapForTesting() {
      return storeHeap;
    }

    @Override
    public synchronized boolean reseek(byte[] row) throws IOException {
      if (row == null) {
        throw new IllegalArgumentException("Row cannot be null.");
      }
      startRegionOperation();
      try {
        // This could be a new thread from the last time we called next().
        MultiVersionConsistencyControl.setThreadReadPoint(this.readPt);
        KeyValue kv = KeyValue.createFirstOnRow(row);
        // use request seek to make use of the lazy seek option. See HBASE-5520
        return this.storeHeap.requestSeek(kv, true, true);
      } finally {
        closeRegionOperation();
      }
    }
  }

  // Utility methods
  /**
   * A utility method to create new instances of HRegion based on the
   * {@link HConstants#REGION_IMPL} configuration property.
   * @param tableDir qualified path of directory where region should be located,
   * usually the table directory.
   * @param log The HLog is the outbound log for any updates to the HRegion
   * (There's a single HLog for all the HRegions on a single HRegionServer.)
   * The log file is a logfile from the previous execution that's
   * custom-computed for this HRegion. The HRegionServer computes and sorts the
   * appropriate log info for this HRegion. If there is a previous log file
   * (implying that the HRegion has been written-to before), then read it from
   * the supplied path.
   * @param fs is the filesystem.
   * @param conf is global configuration settings.
   * @param regionInfo - HRegionInfo that describes the region
   * is new), then read them from the supplied path.
   * @param htd
   * @param rsServices
   * @return the new instance
   */
  public static HRegion newHRegion(Path tableDir, HLog log, FileSystem fs,
      Configuration conf, HRegionInfo regionInfo, final HTableDescriptor htd,
      RegionServerServices rsServices) {
    try {
      @SuppressWarnings("unchecked")
      Class<? extends HRegion> regionClass =
          (Class<? extends HRegion>) conf.getClass(HConstants.REGION_IMPL, HRegion.class);

      Constructor<? extends HRegion> c =
          regionClass.getConstructor(Path.class, HLog.class, FileSystem.class,
              Configuration.class, HRegionInfo.class, HTableDescriptor.class,
              RegionServerServices.class);

      return c.newInstance(tableDir, log, fs, conf, regionInfo, htd, rsServices);
    } catch (Throwable e) {
      // todo: what should I throw here?
      throw new IllegalStateException("Could not instantiate a region instance.", e);
    }
  }

  /**
   * Convenience method creating new HRegions. Used by createTable and by the
   * bootstrap code in the HMaster constructor.
   * Note, this method creates an {@link HLog} for the created region. It
   * needs to be closed explicitly.  Use {@link HRegion#getLog()} to get
   * access.  <b>When done with a region created using this method, you will
   * need to explicitly close the {@link HLog} it created too; it will not be
   * done for you.  Not closing the log will leave at least a daemon thread
   * running.</b>  Call {@link #closeHRegion(HRegion)} and it will do
   * necessary cleanup for you.
   * @param info Info for region to create.
   * @param rootDir Root directory for HBase instance
   * @param conf
   * @param hTableDescriptor
   * @return new HRegion
   *
   * @throws IOException
   */
  public static HRegion createHRegion(final HRegionInfo info, final Path rootDir,
      final Configuration conf, final HTableDescriptor hTableDescriptor)
  throws IOException {
    return createHRegion(info, rootDir, conf, hTableDescriptor, null);
  }

  /**
   * This will do the necessary cleanup a call to {@link #createHRegion(HRegionInfo, Path, Configuration, HTableDescriptor)}
   * requires.  This method will close the region and then close its
   * associated {@link HLog} file.  You use it if you call the other createHRegion,
   * the one that takes an {@link HLog} instance but don't be surprised by the
   * call to the {@link HLog#closeAndDelete()} on the {@link HLog} the
   * HRegion was carrying.
   * @param r
   * @throws IOException
   */
  public static void closeHRegion(final HRegion r) throws IOException {
    if (r == null) return;
    r.close();
    if (r.getLog() == null) return;
    r.getLog().closeAndDelete();
  }

  /**
   * Convenience method creating new HRegions. Used by createTable.
   * The {@link HLog} for the created region needs to be closed explicitly.
   * Use {@link HRegion#getLog()} to get access.
   *
   * @param info Info for region to create.
   * @param rootDir Root directory for HBase instance
   * @param conf
   * @param hTableDescriptor
   * @param hlog shared HLog
   * @param boolean initialize - true to initialize the region
   * @return new HRegion
   *
   * @throws IOException
   */
  public static HRegion createHRegion(final HRegionInfo info, final Path rootDir,
                                      final Configuration conf,
                                      final HTableDescriptor hTableDescriptor,
                                      final HLog hlog,
                                      final boolean initialize)
      throws IOException {
    return createHRegion(info, rootDir, conf, hTableDescriptor,
        hlog, initialize, false);
  }

  /**
   * Convenience method creating new HRegions. Used by createTable.
   * The {@link HLog} for the created region needs to be closed
   * explicitly, if it is not null.
   * Use {@link HRegion#getLog()} to get access.
   *
   * @param info Info for region to create.
   * @param rootDir Root directory for HBase instance
   * @param conf
   * @param hTableDescriptor
   * @param hlog shared HLog
   * @param boolean initialize - true to initialize the region
   * @param boolean ignoreHLog
      - true to skip generate new hlog if it is null, mostly for createTable
   * @return new HRegion
   *
   * @throws IOException
   */
  public static HRegion createHRegion(final HRegionInfo info, final Path rootDir,
                                      final Configuration conf,
                                      final HTableDescriptor hTableDescriptor,
                                      final HLog hlog,
                                      final boolean initialize, final boolean ignoreHLog)
      throws IOException {
    LOG.info("creating HRegion " + info.getTableNameAsString()
        + " HTD == " + hTableDescriptor + " RootDir = " + rootDir +
        " Table name == " + info.getTableNameAsString());

    Path tableDir =
        HTableDescriptor.getTableDir(rootDir, info.getTableName());
    Path regionDir = HRegion.getRegionDir(tableDir, info.getEncodedName());
    FileSystem fs = FileSystem.get(conf);
    fs.mkdirs(regionDir);
    HLog effectiveHLog = hlog;
    if (hlog == null && !ignoreHLog) {
      effectiveHLog = HLogFactory.createHLog(fs, regionDir,
                                             HConstants.HREGION_LOGDIR_NAME, conf);
    }
    HRegion region = HRegion.newHRegion(tableDir,
        effectiveHLog, fs, conf, info, hTableDescriptor, null);
    if (initialize) {
      region.initialize();
    }
    return region;
  }

  public static HRegion createHRegion(final HRegionInfo info, final Path rootDir,
                                      final Configuration conf,
                                      final HTableDescriptor hTableDescriptor,
                                      final HLog hlog)
    throws IOException {
    return createHRegion(info, rootDir, conf, hTableDescriptor, hlog, true);
  }

  /**
   * Open a Region.
   * @param info Info for region to be opened.
   * @param wal HLog for region to use. This method will call
   * HLog#setSequenceNumber(long) passing the result of the call to
   * HRegion#getMinSequenceId() to ensure the log id is properly kept
   * up.  HRegionStore does this every time it opens a new region.
   * @param conf
   * @return new HRegion
   *
   * @throws IOException
   */
  public static HRegion openHRegion(final HRegionInfo info,
      final HTableDescriptor htd, final HLog wal,
      final Configuration conf)
  throws IOException {
    return openHRegion(info, htd, wal, conf, null, null);
  }

  /**
   * Open a Region.
   * @param info Info for region to be opened
   * @param htd
   * @param wal HLog for region to use. This method will call
   * HLog#setSequenceNumber(long) passing the result of the call to
   * HRegion#getMinSequenceId() to ensure the log id is properly kept
   * up.  HRegionStore does this every time it opens a new region.
   * @param conf
   * @param rsServices An interface we can request flushes against.
   * @param reporter An interface we can report progress against.
   * @return new HRegion
   *
   * @throws IOException
   */
  public static HRegion openHRegion(final HRegionInfo info,
    final HTableDescriptor htd, final HLog wal, final Configuration conf,
    final RegionServerServices rsServices,
    final CancelableProgressable reporter)
  throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Opening region: " + info);
    }
    if (info == null) {
      throw new NullPointerException("Passed region info is null");
    }
    Path dir = HTableDescriptor.getTableDir(FSUtils.getRootDir(conf),
      info.getTableName());
    FileSystem fs = null;
    if (rsServices != null) {
      fs = rsServices.getFileSystem();
    }
    if (fs == null) {
      fs = FileSystem.get(conf);
    }
    HRegion r = HRegion.newHRegion(dir, wal, fs, conf, info,
      htd, rsServices);
    return r.openHRegion(reporter);
  }

  public static HRegion openHRegion(Path tableDir, final HRegionInfo info,
      final HTableDescriptor htd, final HLog wal, final Configuration conf)
  throws IOException {
    return openHRegion(tableDir, info, htd, wal, conf, null, null);
  }

  /**
   * Open a Region.
   * @param tableDir Table directory
   * @param info Info for region to be opened.
   * @param wal HLog for region to use. This method will call
   * HLog#setSequenceNumber(long) passing the result of the call to
   * HRegion#getMinSequenceId() to ensure the log id is properly kept
   * up.  HRegionStore does this every time it opens a new region.
   * @param conf
   * @param reporter An interface we can report progress against.
   * @return new HRegion
   *
   * @throws IOException
   */
  public static HRegion openHRegion(final Path tableDir, final HRegionInfo info,
      final HTableDescriptor htd, final HLog wal, final Configuration conf,
      final RegionServerServices rsServices,
      final CancelableProgressable reporter)
  throws IOException {
    if (info == null) throw new NullPointerException("Passed region info is null");
    LOG.info("HRegion.openHRegion Region name ==" + info.getRegionNameAsString());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Opening region: " + info);
    }
    Path dir = HTableDescriptor.getTableDir(tableDir, info.getTableName());
    HRegion r = HRegion.newHRegion(dir, wal, FileSystem.get(conf), conf, info, htd, rsServices);
    return r.openHRegion(reporter);
  }


  /**
   * Open HRegion.
   * Calls initialize and sets sequenceid.
   * @param reporter
   * @return Returns <code>this</code>
   * @throws IOException
   */
  protected HRegion openHRegion(final CancelableProgressable reporter)
  throws IOException {
    checkCompressionCodecs();

    long seqid = initialize(reporter);
    if (this.log != null) {
      this.log.setSequenceNumber(seqid);
    }
    return this;
  }

  private void checkCompressionCodecs() throws IOException {
    for (HColumnDescriptor fam: this.htableDescriptor.getColumnFamilies()) {
      CompressionTest.testCompression(fam.getCompression());
      CompressionTest.testCompression(fam.getCompactionCompression());
    }
  }

  /**
   * Inserts a new region's meta information into the passed
   * <code>meta</code> region. Used by the HMaster bootstrap code adding
   * new table to ROOT table.
   *
   * @param meta META HRegion to be updated
   * @param r HRegion to add to <code>meta</code>
   *
   * @throws IOException
   */
  public static void addRegionToMETA(HRegion meta, HRegion r)
  throws IOException {
    meta.checkResources();
    // The row key is the region name
    byte[] row = r.getRegionName();
    Integer lid = meta.obtainRowLock(row);
    try {
      final long now = EnvironmentEdgeManager.currentTimeMillis();
      final List<KeyValue> edits = new ArrayList<KeyValue>(2);
      edits.add(new KeyValue(row, HConstants.CATALOG_FAMILY,
        HConstants.REGIONINFO_QUALIFIER, now,
        r.getRegionInfo().toByteArray()));
      // Set into the root table the version of the meta table.
      edits.add(new KeyValue(row, HConstants.CATALOG_FAMILY,
        HConstants.META_VERSION_QUALIFIER, now,
        Bytes.toBytes(HConstants.META_VERSION)));
      meta.put(HConstants.CATALOG_FAMILY, edits, lid);
    } finally {
      meta.releaseRowLock(lid);
    }
  }

  /**
   * Deletes all the files for a HRegion
   *
   * @param fs the file system object
   * @param rootdir qualified path of HBase root directory
   * @param info HRegionInfo for region to be deleted
   * @throws IOException
   */
  public static void deleteRegion(FileSystem fs, Path rootdir, HRegionInfo info)
  throws IOException {
    deleteRegion(fs, HRegion.getRegionDir(rootdir, info));
  }

  private static void deleteRegion(FileSystem fs, Path regiondir)
  throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("DELETING region " + regiondir.toString());
    }
    if (!fs.delete(regiondir, true)) {
      LOG.warn("Failed delete of " + regiondir);
    }
  }

  /**
   * Computes the Path of the HRegion
   *
   * @param rootdir qualified path of HBase root directory
   * @param info HRegionInfo for the region
   * @return qualified path of region directory
   */
  public static Path getRegionDir(final Path rootdir, final HRegionInfo info) {
    return new Path(
      HTableDescriptor.getTableDir(rootdir, info.getTableName()),
                                   info.getEncodedName());
  }

  /**
   * Determines if the specified row is within the row range specified by the
   * specified HRegionInfo
   *
   * @param info HRegionInfo that specifies the row range
   * @param row row to be checked
   * @return true if the row is within the range specified by the HRegionInfo
   */
  public static boolean rowIsInRange(HRegionInfo info, final byte [] row) {
    return ((info.getStartKey().length == 0) ||
        (Bytes.compareTo(info.getStartKey(), row) <= 0)) &&
        ((info.getEndKey().length == 0) ||
            (Bytes.compareTo(info.getEndKey(), row) > 0));
  }

  /**
   * Make the directories for a specific column family
   *
   * @param fs the file system
   * @param tabledir base directory where region will live (usually the table dir)
   * @param hri
   * @param colFamily the column family
   * @throws IOException
   */
  public static void makeColumnFamilyDirs(FileSystem fs, Path tabledir,
    final HRegionInfo hri, byte [] colFamily)
  throws IOException {
    Path dir = HStore.getStoreHomedir(tabledir, hri.getEncodedName(), colFamily);
    if (!fs.mkdirs(dir)) {
      LOG.warn("Failed to create " + dir);
    }
  }

  /**
   * Merge two HRegions.  The regions must be adjacent and must not overlap.
   *
   * @param srcA
   * @param srcB
   * @return new merged HRegion
   * @throws IOException
   */
  public static HRegion mergeAdjacent(final HRegion srcA, final HRegion srcB)
  throws IOException {
    HRegion a = srcA;
    HRegion b = srcB;

    // Make sure that srcA comes first; important for key-ordering during
    // write of the merged file.
    if (srcA.getStartKey() == null) {
      if (srcB.getStartKey() == null) {
        throw new IOException("Cannot merge two regions with null start key");
      }
      // A's start key is null but B's isn't. Assume A comes before B
    } else if ((srcB.getStartKey() == null) ||
      (Bytes.compareTo(srcA.getStartKey(), srcB.getStartKey()) > 0)) {
      a = srcB;
      b = srcA;
    }

    if (!(Bytes.compareTo(a.getEndKey(), b.getStartKey()) == 0)) {
      throw new IOException("Cannot merge non-adjacent regions");
    }
    return merge(a, b);
  }

  /**
   * Merge two regions whether they are adjacent or not.
   *
   * @param a region a
   * @param b region b
   * @return new merged region
   * @throws IOException
   */
  public static HRegion merge(HRegion a, HRegion b)
  throws IOException {
    if (!a.getRegionInfo().getTableNameAsString().equals(
        b.getRegionInfo().getTableNameAsString())) {
      throw new IOException("Regions do not belong to the same table");
    }

    FileSystem fs = a.getFilesystem();

    // Make sure each region's cache is empty

    a.flushcache();
    b.flushcache();

    // Compact each region so we only have one store file per family

    a.compactStores(true);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Files for region: " + a);
      listPaths(fs, a.getRegionDir());
    }
    b.compactStores(true);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Files for region: " + b);
      listPaths(fs, b.getRegionDir());
    }

    Configuration conf = a.baseConf;
    HTableDescriptor tabledesc = a.getTableDesc();
    HLog log = a.getLog();
    Path tableDir = a.getTableDir();
    // Presume both are of same region type -- i.e. both user or catalog
    // table regions.  This way can use comparator.
    final byte[] startKey =
      (a.comparator.matchingRows(a.getStartKey(), 0, a.getStartKey().length,
           HConstants.EMPTY_BYTE_ARRAY, 0, HConstants.EMPTY_BYTE_ARRAY.length)
       || b.comparator.matchingRows(b.getStartKey(), 0,
              b.getStartKey().length, HConstants.EMPTY_BYTE_ARRAY, 0,
              HConstants.EMPTY_BYTE_ARRAY.length))
      ? HConstants.EMPTY_BYTE_ARRAY
      : (a.comparator.compareRows(a.getStartKey(), 0, a.getStartKey().length,
             b.getStartKey(), 0, b.getStartKey().length) <= 0
         ? a.getStartKey()
         : b.getStartKey());
    final byte[] endKey =
      (a.comparator.matchingRows(a.getEndKey(), 0, a.getEndKey().length,
           HConstants.EMPTY_BYTE_ARRAY, 0, HConstants.EMPTY_BYTE_ARRAY.length)
       || a.comparator.matchingRows(b.getEndKey(), 0, b.getEndKey().length,
              HConstants.EMPTY_BYTE_ARRAY, 0,
              HConstants.EMPTY_BYTE_ARRAY.length))
      ? HConstants.EMPTY_BYTE_ARRAY
      : (a.comparator.compareRows(a.getEndKey(), 0, a.getEndKey().length,
             b.getEndKey(), 0, b.getEndKey().length) <= 0
         ? b.getEndKey()
         : a.getEndKey());

    HRegionInfo newRegionInfo =
        new HRegionInfo(tabledesc.getName(), startKey, endKey);
    LOG.info("Creating new region " + newRegionInfo.toString());
    String encodedName = newRegionInfo.getEncodedName();
    Path newRegionDir = HRegion.getRegionDir(a.getTableDir(), encodedName);
    if(fs.exists(newRegionDir)) {
      throw new IOException("Cannot merge; target file collision at " +
          newRegionDir);
    }
    fs.mkdirs(newRegionDir);

    LOG.info("starting merge of regions: " + a + " and " + b +
      " into new region " + newRegionInfo.toString() +
        " with start key <" + Bytes.toStringBinary(startKey) + "> and end key <" +
        Bytes.toStringBinary(endKey) + ">");

    // Move HStoreFiles under new region directory
    Map<byte [], List<StoreFile>> byFamily =
      new TreeMap<byte [], List<StoreFile>>(Bytes.BYTES_COMPARATOR);
    byFamily = filesByFamily(byFamily, a.close());
    byFamily = filesByFamily(byFamily, b.close());
    for (Map.Entry<byte [], List<StoreFile>> es : byFamily.entrySet()) {
      byte [] colFamily = es.getKey();
      makeColumnFamilyDirs(fs, tableDir, newRegionInfo, colFamily);
      // Because we compacted the source regions we should have no more than two
      // HStoreFiles per family and there will be no reference store
      List<StoreFile> srcFiles = es.getValue();
      if (srcFiles.size() == 2) {
        long seqA = srcFiles.get(0).getMaxSequenceId();
        long seqB = srcFiles.get(1).getMaxSequenceId();
        if (seqA == seqB) {
          // Can't have same sequenceid since on open of a store, this is what
          // distingushes the files (see the map of stores how its keyed by
          // sequenceid).
          throw new IOException("Files have same sequenceid: " + seqA);
        }
      }
      for (StoreFile hsf: srcFiles) {
        StoreFile.rename(fs, hsf.getPath(),
          StoreFile.getUniqueFile(fs, HStore.getStoreHomedir(tableDir,
            newRegionInfo.getEncodedName(), colFamily)));
      }
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Files for new region");
      listPaths(fs, newRegionDir);
    }
    HRegion dstRegion = HRegion.newHRegion(tableDir, log, fs, conf,
        newRegionInfo, a.getTableDesc(), null);
    dstRegion.readRequestsCount.set(a.readRequestsCount.get() + b.readRequestsCount.get());
    dstRegion.writeRequestsCount.set(a.writeRequestsCount.get() + b.writeRequestsCount.get());
    dstRegion.checkAndMutateChecksFailed.set(
      a.checkAndMutateChecksFailed.get() + b.checkAndMutateChecksFailed.get());
    dstRegion.checkAndMutateChecksPassed.set(
      a.checkAndMutateChecksPassed.get() + b.checkAndMutateChecksPassed.get());
    dstRegion.initialize();
    dstRegion.compactStores();
    if (LOG.isDebugEnabled()) {
      LOG.debug("Files for new region");
      listPaths(fs, dstRegion.getRegionDir());
    }

    // delete out the 'A' region
    HFileArchiver.archiveRegion(fs, FSUtils.getRootDir(a.getBaseConf()), a.getTableDir(),
      a.getRegionDir());
    // delete out the 'B' region
    HFileArchiver.archiveRegion(fs, FSUtils.getRootDir(b.getBaseConf()), b.getTableDir(),
      b.getRegionDir());

    LOG.info("merge completed. New region is " + dstRegion);

    return dstRegion;
  }

  /*
   * Fills a map with a vector of store files keyed by column family.
   * @param byFamily Map to fill.
   * @param storeFiles Store files to process.
   * @param family
   * @return Returns <code>byFamily</code>
   */
  private static Map<byte [], List<StoreFile>> filesByFamily(
      Map<byte [], List<StoreFile>> byFamily, List<StoreFile> storeFiles) {
    for (StoreFile src: storeFiles) {
      byte [] family = src.getFamily();
      List<StoreFile> v = byFamily.get(family);
      if (v == null) {
        v = new ArrayList<StoreFile>();
        byFamily.put(family, v);
      }
      v.add(src);
    }
    return byFamily;
  }

  /**
   * @return True if needs a mojor compaction.
   * @throws IOException
   */
  boolean isMajorCompaction() throws IOException {
    for (Store store : this.stores.values()) {
      if (store.isMajorCompaction()) {
        return true;
      }
    }
    return false;
  }

  /*
   * List the files under the specified directory
   *
   * @param fs
   * @param dir
   * @throws IOException
   */
  private static void listPaths(FileSystem fs, Path dir) throws IOException {
    if (LOG.isDebugEnabled()) {
      FileStatus[] stats = FSUtils.listStatus(fs, dir, null);
      if (stats == null || stats.length == 0) {
        return;
      }
      for (int i = 0; i < stats.length; i++) {
        String path = stats[i].getPath().toString();
        if (stats[i].isDir()) {
          LOG.debug("d " + path);
          listPaths(fs, stats[i].getPath());
        } else {
          LOG.debug("f " + path + " size=" + stats[i].getLen());
        }
      }
    }
  }


  //
  // HBASE-880
  //
  /**
   * @param get get object
   * @param lockid existing lock id, or null for no previous lock
   * @return result
   * @throws IOException read exceptions
   */
  public Result get(final Get get, final Integer lockid) throws IOException {
    checkRow(get.getRow(), "Get");
    // Verify families are all valid
    if (get.hasFamilies()) {
      for (byte [] family: get.familySet()) {
        checkFamily(family);
      }
    } else { // Adding all families to scanner
      for (byte[] family: this.htableDescriptor.getFamiliesKeys()) {
        get.addFamily(family);
      }
    }
    List<KeyValue> results = get(get, true);
    return new Result(results);
  }

  /*
   * Do a get based on the get parameter.
   * @param withCoprocessor invoke coprocessor or not. We don't want to
   * always invoke cp for this private method.
   */
  private List<KeyValue> get(Get get, boolean withCoprocessor)
  throws IOException {

    List<KeyValue> results = new ArrayList<KeyValue>();

    // pre-get CP hook
    if (withCoprocessor && (coprocessorHost != null)) {
       if (coprocessorHost.preGet(get, results)) {
         return results;
       }
    }

    Scan scan = new Scan(get);

    RegionScanner scanner = null;
    try {
      scanner = getScanner(scan);
      scanner.next(results);
    } finally {
      if (scanner != null)
        scanner.close();
    }

    // post-get CP hook
    if (withCoprocessor && (coprocessorHost != null)) {
      coprocessorHost.postGet(get, results);
    }

    // do after lock

    this.metricsRegion.updateGet();

    return results;
  }

  public void mutateRow(RowMutations rm) throws IOException {
    mutateRowsWithLocks(rm.getMutations(), Collections.singleton(rm.getRow()));
  }

  /**
   * Perform atomic mutations within the region.
   * @param mutations The list of mutations to perform.
   * <code>mutations</code> can contain operations for multiple rows.
   * Caller has to ensure that all rows are contained in this region.
   * @param rowsToLock Rows to lock
   * If multiple rows are locked care should be taken that
   * <code>rowsToLock</code> is sorted in order to avoid deadlocks.
   * @throws IOException
   */
  public void mutateRowsWithLocks(Collection<Mutation> mutations,
      Collection<byte[]> rowsToLock) throws IOException {

    MultiRowMutationProcessor proc =
        new MultiRowMutationProcessor(mutations, rowsToLock);
    processRowsWithLocks(proc, -1);
  }

  /**
   * Performs atomic multiple reads and writes on a given row.
   *
   * @param processor The object defines the reads and writes to a row.
   */
  public void processRowsWithLocks(RowProcessor<?,?> processor)
      throws IOException {
    processRowsWithLocks(processor, rowProcessorTimeout);
  }

  /**
   * Performs atomic multiple reads and writes on a given row.
   *
   * @param processor The object defines the reads and writes to a row.
   * @param timeout The timeout of the processor.process() execution
   *                Use a negative number to switch off the time bound
   */
  public void processRowsWithLocks(RowProcessor<?,?> processor, long timeout)
      throws IOException {

    for (byte[] row : processor.getRowsToLock()) {
      checkRow(row, "processRowsWithLocks");
    }
    if (!processor.readOnly()) {
      checkReadOnly();
    }
    checkResources();

    startRegionOperation();
    WALEdit walEdit = new WALEdit();

    // 1. Run pre-process hook
    processor.preProcess(this, walEdit);

    // Short circuit the read only case
    if (processor.readOnly()) {
      try {
        long now = EnvironmentEdgeManager.currentTimeMillis();
        doProcessRowWithTimeout(
            processor, now, this, null, null, timeout);
        processor.postProcess(this, walEdit);
      } catch (IOException e) {
        throw e;
      } finally {
        closeRegionOperation();
      }
      return;
    }

    MultiVersionConsistencyControl.WriteEntry writeEntry = null;
    boolean locked = false;
    boolean walSyncSuccessful = false;
    List<Integer> acquiredLocks = null;
    long addedSize = 0;
    List<KeyValue> mutations = new ArrayList<KeyValue>();
    Collection<byte[]> rowsToLock = processor.getRowsToLock();
    try {
      // 2. Acquire the row lock(s)
      acquiredLocks = new ArrayList<Integer>(rowsToLock.size());
      for (byte[] row : rowsToLock) {
        // Attempt to lock all involved rows, fail if one lock times out
        Integer lid = getLock(null, row, true);
        if (lid == null) {
          throw new IOException("Failed to acquire lock on "
              + Bytes.toStringBinary(row));
        }
        acquiredLocks.add(lid);
      }
      // 3. Region lock
      this.updatesLock.readLock().lock();
      locked = true;

      long now = EnvironmentEdgeManager.currentTimeMillis();
      try {
        // 4. Let the processor scan the rows, generate mutations and add
        //    waledits
        doProcessRowWithTimeout(
            processor, now, this, mutations, walEdit, timeout);

        if (!mutations.isEmpty()) {
          // 5. Get a mvcc write number
          writeEntry = mvcc.beginMemstoreInsert();
          // 6. Apply to memstore
          for (KeyValue kv : mutations) {
            kv.setMemstoreTS(writeEntry.getWriteNumber());
            byte[] family = kv.getFamily();
            checkFamily(family);
            addedSize += stores.get(family).add(kv);
          }

          long txid = 0;
          // 7. Append no sync
          if (!walEdit.isEmpty()) {
            txid = this.log.appendNoSync(this.regionInfo,
                this.htableDescriptor.getName(), walEdit,
                processor.getClusterId(), now, this.htableDescriptor);
          }
          // 8. Release region lock
          if (locked) {
            this.updatesLock.readLock().unlock();
            locked = false;
          }

          // 9. Release row lock(s)
          if (acquiredLocks != null) {
            for (Integer lid : acquiredLocks) {
              releaseRowLock(lid);
            }
            acquiredLocks = null;
          }
          // 10. Sync edit log
          if (txid != 0) {
            syncOrDefer(txid);
          }
          walSyncSuccessful = true;
        }
      } finally {
        if (!mutations.isEmpty() && !walSyncSuccessful) {
          LOG.warn("Wal sync failed. Roll back " + mutations.size() +
              " memstore keyvalues for row(s):" +
              processor.getRowsToLock().iterator().next() + "...");
          for (KeyValue kv : mutations) {
            stores.get(kv.getFamily()).rollback(kv);
          }
        }
        // 11. Roll mvcc forward
        if (writeEntry != null) {
          mvcc.completeMemstoreInsert(writeEntry);
          writeEntry = null;
        }
        if (locked) {
          this.updatesLock.readLock().unlock();
          locked = false;
        }
        if (acquiredLocks != null) {
          for (Integer lid : acquiredLocks) {
            releaseRowLock(lid);
          }
        }

      }

      // 12. Run post-process hook
      processor.postProcess(this, walEdit);

    } catch (IOException e) {
      throw e;
    } finally {
      closeRegionOperation();
      if (!mutations.isEmpty() &&
          isFlushSize(this.addAndGetGlobalMemstoreSize(addedSize))) {
        requestFlush();
      }
    }
  }

  private void doProcessRowWithTimeout(final RowProcessor<?,?> processor,
                                       final long now,
                                       final HRegion region,
                                       final List<KeyValue> mutations,
                                       final WALEdit walEdit,
                                       final long timeout) throws IOException {
    // Short circuit the no time bound case.
    if (timeout < 0) {
      try {
        processor.process(now, region, mutations, walEdit);
      } catch (IOException e) {
        LOG.warn("RowProcessor:" + processor.getClass().getName() +
            " throws Exception on row(s):" +
            Bytes.toStringBinary(
              processor.getRowsToLock().iterator().next()) + "...", e);
        throw e;
      }
      return;
    }

    // Case with time bound
    FutureTask<Void> task =
      new FutureTask<Void>(new Callable<Void>() {
        @Override
        public Void call() throws IOException {
          try {
            processor.process(now, region, mutations, walEdit);
            return null;
          } catch (IOException e) {
            LOG.warn("RowProcessor:" + processor.getClass().getName() +
                " throws Exception on row(s):" +
                Bytes.toStringBinary(
                    processor.getRowsToLock().iterator().next()) + "...", e);
            throw e;
          }
        }
      });
    rowProcessorExecutor.execute(task);
    try {
      task.get(timeout, TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      LOG.error("RowProcessor timeout:" + timeout + " ms on row(s):" +
          Bytes.toStringBinary(processor.getRowsToLock().iterator().next()) +
          "...");
      throw new IOException(te);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  // TODO: There's a lot of boiler plate code identical
  // to increment... See how to better unify that.
  /**
   * Perform one or more append operations on a row.
   *
   * @param append
   * @param lockid
   * @param writeToWAL
   * @return new keyvalues after increment
   * @throws IOException
   */
  public Result append(Append append, Integer lockid, boolean writeToWAL)
      throws IOException {
    byte[] row = append.getRow();
    checkRow(row, "append");
    boolean flush = false;
    WALEdit walEdits = null;
    List<KeyValue> allKVs = new ArrayList<KeyValue>(append.size());
    Map<Store, List<KeyValue>> tempMemstore = new HashMap<Store, List<KeyValue>>();

    long size = 0;
    long txid = 0;

    // Lock row
    startRegionOperation();
    this.writeRequestsCount.increment();
    WriteEntry w = null;
    try {
      Integer lid = getLock(lockid, row, true);
      this.updatesLock.readLock().lock();
      // wait for all prior MVCC transactions to finish - while we hold the row lock
      // (so that we are guaranteed to see the latest state)
      mvcc.completeMemstoreInsert(mvcc.beginMemstoreInsert());
      // now start my own transaction
      w = mvcc.beginMemstoreInsert();
      try {
        long now = EnvironmentEdgeManager.currentTimeMillis();
        // Process each family
        for (Map.Entry<byte[], List<KeyValue>> family : append.getFamilyMap()
            .entrySet()) {

          Store store = stores.get(family.getKey());
          List<KeyValue> kvs = new ArrayList<KeyValue>(family.getValue().size());

          // Get previous values for all columns in this family
          Get get = new Get(row);
          for (KeyValue kv : family.getValue()) {
            get.addColumn(family.getKey(), kv.getQualifier());
          }
          List<KeyValue> results = get(get, false);

          // Iterate the input columns and update existing values if they were
          // found, otherwise add new column initialized to the append value

          // Avoid as much copying as possible. Every byte is copied at most
          // once.
          // Would be nice if KeyValue had scatter/gather logic
          int idx = 0;
          for (KeyValue kv : family.getValue()) {
            KeyValue newKV;
            if (idx < results.size()
                && results.get(idx).matchingQualifier(kv.getBuffer(),
                    kv.getQualifierOffset(), kv.getQualifierLength())) {
              KeyValue oldKv = results.get(idx);
              // allocate an empty kv once
              newKV = new KeyValue(row.length, kv.getFamilyLength(),
                  kv.getQualifierLength(), now, KeyValue.Type.Put,
                  oldKv.getValueLength() + kv.getValueLength());
              // copy in the value
              System.arraycopy(oldKv.getBuffer(), oldKv.getValueOffset(),
                  newKV.getBuffer(), newKV.getValueOffset(),
                  oldKv.getValueLength());
              System.arraycopy(kv.getBuffer(), kv.getValueOffset(),
                  newKV.getBuffer(),
                  newKV.getValueOffset() + oldKv.getValueLength(),
                  kv.getValueLength());
              idx++;
            } else {
              // allocate an empty kv once
              newKV = new KeyValue(row.length, kv.getFamilyLength(),
                  kv.getQualifierLength(), now, KeyValue.Type.Put,
                  kv.getValueLength());
              // copy in the value
              System.arraycopy(kv.getBuffer(), kv.getValueOffset(),
                  newKV.getBuffer(), newKV.getValueOffset(),
                  kv.getValueLength());
            }
            // copy in row, family, and qualifier
            System.arraycopy(kv.getBuffer(), kv.getRowOffset(),
                newKV.getBuffer(), newKV.getRowOffset(), kv.getRowLength());
            System.arraycopy(kv.getBuffer(), kv.getFamilyOffset(),
                newKV.getBuffer(), newKV.getFamilyOffset(),
                kv.getFamilyLength());
            System.arraycopy(kv.getBuffer(), kv.getQualifierOffset(),
                newKV.getBuffer(), newKV.getQualifierOffset(),
                kv.getQualifierLength());

            newKV.setMemstoreTS(w.getWriteNumber());
            kvs.add(newKV);

            // Append update to WAL
            if (writeToWAL) {
              if (walEdits == null) {
                walEdits = new WALEdit();
              }
              walEdits.add(newKV);
            }
          }

          //store the kvs to the temporary memstore before writing HLog
          tempMemstore.put(store, kvs);
        }

        // Actually write to WAL now
        if (writeToWAL) {
          // Using default cluster id, as this can only happen in the orginating
          // cluster. A slave cluster receives the final value (not the delta)
          // as a Put.
          txid = this.log.appendNoSync(regionInfo,
              this.htableDescriptor.getName(), walEdits,
              HConstants.DEFAULT_CLUSTER_ID, EnvironmentEdgeManager.currentTimeMillis(),
              this.htableDescriptor);
        }

        //Actually write to Memstore now
        for (Map.Entry<Store, List<KeyValue>> entry : tempMemstore.entrySet()) {
          Store store = entry.getKey();
          if (store.getFamily().getMaxVersions() == 1) {
            // upsert if VERSIONS for this CF == 1
            size += store.upsert(entry.getValue(), getSmallestReadPoint());
          } else {
            // otherwise keep older versions around
            for (KeyValue kv : entry.getValue()) {
              size += store.add(kv);
            }
          }
          allKVs.addAll(entry.getValue());
        }
        size = this.addAndGetGlobalMemstoreSize(size);
        flush = isFlushSize(size);
      } finally {
        this.updatesLock.readLock().unlock();
        releaseRowLock(lid);
      }
      if (writeToWAL) {
        syncOrDefer(txid); // sync the transaction log outside the rowlock
      }
    } finally {
      if (w != null) {
        mvcc.completeMemstoreInsert(w);
      }
      closeRegionOperation();
    }

    this.metricsRegion.updateAppend();


    if (flush) {
      // Request a cache flush. Do it outside update lock.
      requestFlush();
    }


    return append.isReturnResults() ? new Result(allKVs) : null;
  }

  /**
   * Perform one or more increment operations on a row.
   * @param increment
   * @param lockid
   * @param writeToWAL
   * @return new keyvalues after increment
   * @throws IOException
   */
  public Result increment(Increment increment, Integer lockid,
      boolean writeToWAL)
  throws IOException {
    byte [] row = increment.getRow();
    checkRow(row, "increment");
    TimeRange tr = increment.getTimeRange();
    boolean flush = false;
    WALEdit walEdits = null;
    List<KeyValue> allKVs = new ArrayList<KeyValue>(increment.numColumns());
    Map<Store, List<KeyValue>> tempMemstore = new HashMap<Store, List<KeyValue>>();

    long size = 0;
    long txid = 0;

    // Lock row
    startRegionOperation();
    this.writeRequestsCount.increment();
    WriteEntry w = null;
    try {
      Integer lid = getLock(lockid, row, true);
      this.updatesLock.readLock().lock();
      // wait for all prior MVCC transactions to finish - while we hold the row lock
      // (so that we are guaranteed to see the latest state)
      mvcc.completeMemstoreInsert(mvcc.beginMemstoreInsert());
      // now start my own transaction
      w = mvcc.beginMemstoreInsert();
      try {
        long now = EnvironmentEdgeManager.currentTimeMillis();
        // Process each family
        for (Map.Entry<byte [], NavigableMap<byte [], Long>> family :
          increment.getFamilyMap().entrySet()) {

          Store store = stores.get(family.getKey());
          List<KeyValue> kvs = new ArrayList<KeyValue>(family.getValue().size());

          // Get previous values for all columns in this family
          Get get = new Get(row);
          for (Map.Entry<byte [], Long> column : family.getValue().entrySet()) {
            get.addColumn(family.getKey(), column.getKey());
          }
          get.setTimeRange(tr.getMin(), tr.getMax());
          List<KeyValue> results = get(get, false);

          // Iterate the input columns and update existing values if they were
          // found, otherwise add new column initialized to the increment amount
          int idx = 0;
          for (Map.Entry<byte [], Long> column : family.getValue().entrySet()) {
            long amount = column.getValue();
            if (idx < results.size() &&
                results.get(idx).matchingQualifier(column.getKey())) {
              KeyValue kv = results.get(idx);
              if(kv.getValueLength() == Bytes.SIZEOF_LONG) {
                amount += Bytes.toLong(kv.getBuffer(), kv.getValueOffset(), Bytes.SIZEOF_LONG);
              } else {
                // throw DoNotRetryIOException instead of IllegalArgumentException
                throw new DoNotRetryIOException(
                    "Attempted to increment field that isn't 64 bits wide");
              }
              idx++;
            }

            // Append new incremented KeyValue to list
            KeyValue newKV = new KeyValue(row, family.getKey(), column.getKey(),
                now, Bytes.toBytes(amount));
            newKV.setMemstoreTS(w.getWriteNumber());
            kvs.add(newKV);

            // Prepare WAL updates
            if (writeToWAL) {
              if (walEdits == null) {
                walEdits = new WALEdit();
              }
              walEdits.add(newKV);
            }
          }

          //store the kvs to the temporary memstore before writing HLog
          tempMemstore.put(store, kvs);
        }

        // Actually write to WAL now
        if (writeToWAL) {
          // Using default cluster id, as this can only happen in the orginating
          // cluster. A slave cluster receives the final value (not the delta)
          // as a Put.
          txid = this.log.appendNoSync(regionInfo, this.htableDescriptor.getName(),
              walEdits, HConstants.DEFAULT_CLUSTER_ID, EnvironmentEdgeManager.currentTimeMillis(),
              this.htableDescriptor);
        }

        //Actually write to Memstore now
        for (Map.Entry<Store, List<KeyValue>> entry : tempMemstore.entrySet()) {
          Store store = entry.getKey();
          if (store.getFamily().getMaxVersions() == 1) {
            // upsert if VERSIONS for this CF == 1
            size += store.upsert(entry.getValue(), getSmallestReadPoint());
          } else {
            // otherwise keep older versions around
            for (KeyValue kv : entry.getValue()) {
              size += store.add(kv);
            }
          }
          allKVs.addAll(entry.getValue());
        }
        size = this.addAndGetGlobalMemstoreSize(size);
        flush = isFlushSize(size);
      } finally {
        this.updatesLock.readLock().unlock();
        releaseRowLock(lid);
      }
      if (writeToWAL) {
        syncOrDefer(txid); // sync the transaction log outside the rowlock
      }
    } finally {
      if (w != null) {
        mvcc.completeMemstoreInsert(w);
      }
      closeRegionOperation();
      this.metricsRegion.updateIncrement();
    }

    if (flush) {
      // Request a cache flush.  Do it outside update lock.
      requestFlush();
    }

    return new Result(allKVs);
  }

  //
  // New HBASE-880 Helpers
  //

  private void checkFamily(final byte [] family)
  throws NoSuchColumnFamilyException {
    if (!this.htableDescriptor.hasFamily(family)) {
      throw new NoSuchColumnFamilyException("Column family " +
          Bytes.toString(family) + " does not exist in region " + this
          + " in table " + this.htableDescriptor);
    }
  }

  public static final long FIXED_OVERHEAD = ClassSize.align(
      ClassSize.OBJECT +
      ClassSize.ARRAY +
      40 * ClassSize.REFERENCE + Bytes.SIZEOF_INT +
      (7 * Bytes.SIZEOF_LONG) +
      Bytes.SIZEOF_BOOLEAN);

  public static final long DEEP_OVERHEAD = FIXED_OVERHEAD +
      ClassSize.OBJECT + // closeLock
      (2 * ClassSize.ATOMIC_BOOLEAN) + // closed, closing
      (3 * ClassSize.ATOMIC_LONG) + // memStoreSize, numPutsWithoutWAL, dataInMemoryWithoutWAL
      ClassSize.ATOMIC_INTEGER + // lockIdGenerator
      (3 * ClassSize.CONCURRENT_HASHMAP) +  // lockedRows, lockIds, scannerReadPoints
      WriteState.HEAP_SIZE + // writestate
      ClassSize.CONCURRENT_SKIPLISTMAP + ClassSize.CONCURRENT_SKIPLISTMAP_ENTRY + // stores
      (2 * ClassSize.REENTRANT_LOCK) + // lock, updatesLock
      ClassSize.ARRAYLIST + // recentFlushes
      MultiVersionConsistencyControl.FIXED_SIZE // mvcc
      ;

  @Override
  public long heapSize() {
    long heapSize = DEEP_OVERHEAD;
    for (Store store : this.stores.values()) {
      heapSize += store.heapSize();
    }
    // this does not take into account row locks, recent flushes, mvcc entries
    return heapSize;
  }

  /*
   * This method calls System.exit.
   * @param message Message to print out.  May be null.
   */
  private static void printUsageAndExit(final String message) {
    if (message != null && message.length() > 0) System.out.println(message);
    System.out.println("Usage: HRegion CATLALOG_TABLE_DIR [major_compact]");
    System.out.println("Options:");
    System.out.println(" major_compact  Pass this option to major compact " +
      "passed region.");
    System.out.println("Default outputs scan of passed region.");
    System.exit(1);
  }

  /**
   * Registers a new CoprocessorProtocol subclass and instance to
   * be available for handling {@link HRegion#exec(Exec)} calls.
   *
   * <p>
   * Only a single protocol type/handler combination may be registered per
   * region.
   * After the first registration, subsequent calls with the same protocol type
   * will fail with a return value of {@code false}.
   * </p>
   * @param protocol a {@code CoprocessorProtocol} subinterface defining the
   * protocol methods
   * @param handler an instance implementing the interface
   * @param <T> the protocol type
   * @return {@code true} if the registration was successful, {@code false}
   * otherwise
   */
  @Deprecated
  public <T extends CoprocessorProtocol> boolean registerProtocol(
      Class<T> protocol, T handler) {

    /* No stacking of protocol handlers is currently allowed.  The
     * first to claim wins!
     */
    if (protocolHandlers.containsKey(protocol)) {
      LOG.error("Protocol "+protocol.getName()+
          " already registered, rejecting request from "+
          handler
      );
      return false;
    }

    protocolHandlers.putInstance(protocol, handler);
    protocolHandlerNames.put(protocol.getName(), protocol);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Registered protocol handler: region="+
          Bytes.toStringBinary(getRegionName())+" protocol="+protocol.getName());
    }
    return true;
  }

  /**
   * Registers a new protocol buffer {@link Service} subclass as a coprocessor endpoint to
   * be available for handling
   * {@link HRegion#execService(com.google.protobuf.RpcController, org.apache.hadoop.hbase.protobuf.generated.ClientProtos.CoprocessorServiceCall)}} calls.
   *
   * <p>
   * Only a single instance may be registered per region for a given {@link Service} subclass (the
   * instances are keyed on {@link com.google.protobuf.Descriptors.ServiceDescriptor#getFullName()}.
   * After the first registration, subsequent calls with the same service name will fail with
   * a return value of {@code false}.
   * </p>
   * @param instance the {@code Service} subclass instance to expose as a coprocessor endpoint
   * @return {@code true} if the registration was successful, {@code false}
   * otherwise
   */
  public boolean registerService(Service instance) {
    /*
     * No stacking of instances is allowed for a single service name
     */
    Descriptors.ServiceDescriptor serviceDesc = instance.getDescriptorForType();
    if (coprocessorServiceHandlers.containsKey(serviceDesc.getFullName())) {
      LOG.error("Coprocessor service "+serviceDesc.getFullName()+
          " already registered, rejecting request from "+instance
      );
      return false;
    }

    coprocessorServiceHandlers.put(serviceDesc.getFullName(), instance);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Registered coprocessor service: region="+
          Bytes.toStringBinary(getRegionName())+" service="+serviceDesc.getFullName());
    }
    return true;
  }

  /**
   * Executes a single {@link org.apache.hadoop.hbase.ipc.CoprocessorProtocol}
   * method using the registered protocol handlers.
   * {@link CoprocessorProtocol} implementations must be registered via the
   * {@link org.apache.hadoop.hbase.regionserver.HRegion#registerProtocol(Class, org.apache.hadoop.hbase.ipc.CoprocessorProtocol)}
   * method before they are available.
   *
   * @param call an {@code Exec} instance identifying the protocol, method name,
   *     and parameters for the method invocation
   * @return an {@code ExecResult} instance containing the region name of the
   *     invocation and the return value
   * @throws IOException if no registered protocol handler is found or an error
   *     occurs during the invocation
   * @see org.apache.hadoop.hbase.regionserver.HRegion#registerProtocol(Class, org.apache.hadoop.hbase.ipc.CoprocessorProtocol)
   */
  @Deprecated
  public ExecResult exec(Exec call)
      throws IOException {
    Class<? extends CoprocessorProtocol> protocol = call.getProtocol();
    if (protocol == null) {
      String protocolName = call.getProtocolName();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Received dynamic protocol exec call with protocolName " + protocolName);
      }
      // detect the actual protocol class
      protocol  = protocolHandlerNames.get(protocolName);
      if (protocol == null) {
        throw new HBaseRPC.UnknownProtocolException(null,
            "No matching handler for protocol "+protocolName+
            " in region "+Bytes.toStringBinary(getRegionName()));
      }
    }
    if (!protocolHandlers.containsKey(protocol)) {
      throw new HBaseRPC.UnknownProtocolException(protocol,
          "No matching handler for protocol "+protocol.getName()+
          " in region "+Bytes.toStringBinary(getRegionName()));
    }

    CoprocessorProtocol handler = protocolHandlers.getInstance(protocol);
    Object value;

    try {
      Method method = protocol.getMethod(
          call.getMethodName(), call.getParameterClasses());
      method.setAccessible(true);

      value = method.invoke(handler, call.getParameters());
    } catch (InvocationTargetException e) {
      Throwable target = e.getTargetException();
      if (target instanceof IOException) {
        throw (IOException)target;
      }
      IOException ioe = new IOException(target.toString());
      ioe.setStackTrace(target.getStackTrace());
      throw ioe;
    } catch (Throwable e) {
      if (!(e instanceof IOException)) {
        LOG.error("Unexpected throwable object ", e);
      }
      IOException ioe = new IOException(e.toString());
      ioe.setStackTrace(e.getStackTrace());
      throw ioe;
    }

    return new ExecResult(getRegionName(), value);
  }

  /**
   * Executes a single protocol buffer coprocessor endpoint {@link Service} method using
   * the registered protocol handlers.  {@link Service} implementations must be registered via the
   * {@link org.apache.hadoop.hbase.regionserver.HRegion#registerService(com.google.protobuf.Service)}
   * method before they are available.
   *
   * @param controller an {@code RpcContoller} implementation to pass to the invoked service
   * @param call a {@code CoprocessorServiceCall} instance identifying the service, method,
   *     and parameters for the method invocation
   * @return a protocol buffer {@code Message} instance containing the method's result
   * @throws IOException if no registered service handler is found or an error
   *     occurs during the invocation
   * @see org.apache.hadoop.hbase.regionserver.HRegion#registerService(com.google.protobuf.Service)
   */
  public Message execService(RpcController controller, CoprocessorServiceCall call)
      throws IOException {
    String serviceName = call.getServiceName();
    String methodName = call.getMethodName();
    if (!coprocessorServiceHandlers.containsKey(serviceName)) {
      throw new HBaseRPC.UnknownProtocolException(null,
          "No registered coprocessor service found for name "+serviceName+
          " in region "+Bytes.toStringBinary(getRegionName()));
    }

    Service service = coprocessorServiceHandlers.get(serviceName);
    Descriptors.ServiceDescriptor serviceDesc = service.getDescriptorForType();
    Descriptors.MethodDescriptor methodDesc = serviceDesc.findMethodByName(methodName);
    if (methodDesc == null) {
      throw new HBaseRPC.UnknownProtocolException(service.getClass(),
          "Unknown method "+methodName+" called on service "+serviceName+
              " in region "+Bytes.toStringBinary(getRegionName()));
    }

    Message request = service.getRequestPrototype(methodDesc).newBuilderForType()
        .mergeFrom(call.getRequest()).build();
    final Message.Builder responseBuilder =
        service.getResponsePrototype(methodDesc).newBuilderForType();
    service.callMethod(methodDesc, controller, request, new RpcCallback<Message>() {
      @Override
      public void run(Message message) {
        if (message != null) {
          responseBuilder.mergeFrom(message);
        }
      }
    });

    return responseBuilder.build();
  }

  /*
   * Process table.
   * Do major compaction or list content.
   * @param fs
   * @param p
   * @param log
   * @param c
   * @param majorCompact
   * @throws IOException
   */
  private static void processTable(final FileSystem fs, final Path p,
      final HLog log, final Configuration c,
      final boolean majorCompact)
  throws IOException {
    HRegion region = null;
    String rootStr = Bytes.toString(HConstants.ROOT_TABLE_NAME);
    String metaStr = Bytes.toString(HConstants.META_TABLE_NAME);
    // Currently expects tables have one region only.
    if (p.getName().startsWith(rootStr)) {
      region = HRegion.newHRegion(p, log, fs, c, HRegionInfo.ROOT_REGIONINFO,
        HTableDescriptor.ROOT_TABLEDESC, null);
    } else if (p.getName().startsWith(metaStr)) {
      region = HRegion.newHRegion(p, log, fs, c,
        HRegionInfo.FIRST_META_REGIONINFO, HTableDescriptor.META_TABLEDESC, null);
    } else {
      throw new IOException("Not a known catalog table: " + p.toString());
    }
    try {
      region.initialize();
      if (majorCompact) {
        region.compactStores(true);
      } else {
        // Default behavior
        Scan scan = new Scan();
        // scan.addFamily(HConstants.CATALOG_FAMILY);
        RegionScanner scanner = region.getScanner(scan);
        try {
          List<KeyValue> kvs = new ArrayList<KeyValue>();
          boolean done = false;
          do {
            kvs.clear();
            done = scanner.next(kvs);
            if (kvs.size() > 0) LOG.info(kvs);
          } while (done);
        } finally {
          scanner.close();
        }
      }
    } finally {
      region.close();
    }
  }

  boolean shouldForceSplit() {
    return this.splitRequest;
  }

  byte[] getExplicitSplitPoint() {
    return this.explicitSplitPoint;
  }

  void forceSplit(byte[] sp) {
    // NOTE : this HRegion will go away after the forced split is successfull
    //        therefore, no reason to clear this value
    this.splitRequest = true;
    if (sp != null) {
      this.explicitSplitPoint = sp;
    }
  }

  void clearSplit_TESTS_ONLY() {
    this.splitRequest = false;
  }

  /**
   * Give the region a chance to prepare before it is split.
   */
  protected void prepareToSplit() {
    // nothing
  }

  /**
   * Return the splitpoint. null indicates the region isn't splittable
   * If the splitpoint isn't explicitly specified, it will go over the stores
   * to find the best splitpoint. Currently the criteria of best splitpoint
   * is based on the size of the store.
   */
  public byte[] checkSplit() {
    // Can't split ROOT/META
    if (this.regionInfo.isMetaTable()) {
      if (shouldForceSplit()) {
        LOG.warn("Cannot split root/meta regions in HBase 0.20 and above");
      }
      return null;
    }

    if (!splitPolicy.shouldSplit()) {
      return null;
    }

    byte[] ret = splitPolicy.getSplitPoint();

    if (ret != null) {
      try {
        checkRow(ret, "calculated split");
      } catch (IOException e) {
        LOG.error("Ignoring invalid split", e);
        return null;
      }
    }
    return ret;
  }

  /**
   * @return The priority that this region should have in the compaction queue
   */
  public int getCompactPriority() {
    int count = Integer.MAX_VALUE;
    for (Store store : stores.values()) {
      count = Math.min(count, store.getCompactPriority());
    }
    return count;
  }

  /**
   * Checks every store to see if one has too many
   * store files
   * @return true if any store has too many store files
   */
  public boolean needsCompaction() {
    for (Store store : stores.values()) {
      if(store.needsCompaction()) {
        return true;
      }
    }
    return false;
  }

  /** @return the coprocessor host */
  public RegionCoprocessorHost getCoprocessorHost() {
    return coprocessorHost;
  }

  /** @param coprocessorHost the new coprocessor host */
  public void setCoprocessorHost(final RegionCoprocessorHost coprocessorHost) {
    this.coprocessorHost = coprocessorHost;
  }

  /**
   * This method needs to be called before any public call that reads or
   * modifies data. It has to be called just before a try.
   * #closeRegionOperation needs to be called in the try's finally block
   * Acquires a read lock and checks if the region is closing or closed.
   * @throws NotServingRegionException when the region is closing or closed
   */
  private void startRegionOperation() throws NotServingRegionException {
    if (this.closing.get()) {
      throw new NotServingRegionException(regionInfo.getRegionNameAsString() +
          " is closing");
    }
    lock.readLock().lock();
    if (this.closed.get()) {
      lock.readLock().unlock();
      throw new NotServingRegionException(regionInfo.getRegionNameAsString() +
          " is closed");
    }
  }

  /**
   * Closes the lock. This needs to be called in the finally block corresponding
   * to the try block of #startRegionOperation
   */
  private void closeRegionOperation(){
    lock.readLock().unlock();
  }

  /**
   * This method needs to be called before any public call that reads or
   * modifies stores in bulk. It has to be called just before a try.
   * #closeBulkRegionOperation needs to be called in the try's finally block
   * Acquires a writelock and checks if the region is closing or closed.
   * @throws NotServingRegionException when the region is closing or closed
   */
  private void startBulkRegionOperation(boolean writeLockNeeded)
  throws NotServingRegionException {
    if (this.closing.get()) {
      throw new NotServingRegionException(regionInfo.getRegionNameAsString() +
          " is closing");
    }
    if (writeLockNeeded) lock.writeLock().lock();
    else lock.readLock().lock();
    if (this.closed.get()) {
      if (writeLockNeeded) lock.writeLock().unlock();
      else lock.readLock().unlock();
      throw new NotServingRegionException(regionInfo.getRegionNameAsString() +
          " is closed");
    }
  }

  /**
   * Closes the lock. This needs to be called in the finally block corresponding
   * to the try block of #startRegionOperation
   */
  private void closeBulkRegionOperation(){
    if (lock.writeLock().isHeldByCurrentThread()) lock.writeLock().unlock();
    else lock.readLock().unlock();
  }

  /**
   * Update counters for numer of puts without wal and the size of possible data loss.
   * These information are exposed by the region server metrics.
   */
  private void recordPutWithoutWal(final Map<byte [], List<KeyValue>> familyMap) {
    numPutsWithoutWAL.increment();
    if (numPutsWithoutWAL.get() <= 1) {
      LOG.info("writing data to region " + this +
               " with WAL disabled. Data may be lost in the event of a crash.");
    }

    long putSize = 0;
    for (List<KeyValue> edits : familyMap.values()) {
      for (KeyValue kv : edits) {
        putSize += kv.getKeyLength() + kv.getValueLength();
      }
    }

    dataInMemoryWithoutWAL.add(putSize);
  }

  /**
   * Calls sync with the given transaction ID if the region's table is not
   * deferring it.
   * @param txid should sync up to which transaction
   * @throws IOException If anything goes wrong with DFS
   */
  private void syncOrDefer(long txid) throws IOException {
    if (this.regionInfo.isMetaRegion() ||
      !this.htableDescriptor.isDeferredLogFlush()) {
      this.log.sync(txid);
    }
  }

  /**
   * A mocked list implementaion - discards all updates.
   */
  private static final List<KeyValue> MOCKED_LIST = new AbstractList<KeyValue>() {

    @Override
    public void add(int index, KeyValue element) {
      // do nothing
    }

    @Override
    public boolean addAll(int index, Collection<? extends KeyValue> c) {
      return false; // this list is never changed as a result of an update
    }

    @Override
    public KeyValue get(int index) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
      return 0;
    }
  };


  /**
   * Facility for dumping and compacting catalog tables.
   * Only does catalog tables since these are only tables we for sure know
   * schema on.  For usage run:
   * <pre>
   *   ./bin/hbase org.apache.hadoop.hbase.regionserver.HRegion
   * </pre>
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) throws IOException {
    if (args.length < 1) {
      printUsageAndExit(null);
    }
    boolean majorCompact = false;
    if (args.length > 1) {
      if (!args[1].toLowerCase().startsWith("major")) {
        printUsageAndExit("ERROR: Unrecognized option <" + args[1] + ">");
      }
      majorCompact = true;
    }
    final Path tableDir = new Path(args[0]);
    final Configuration c = HBaseConfiguration.create();
    final FileSystem fs = FileSystem.get(c);
    final Path logdir = new Path(c.get("hbase.tmp.dir"));
    final String logname = "hlog" + tableDir.getName()
      + EnvironmentEdgeManager.currentTimeMillis();

    final HLog log = HLogFactory.createHLog(fs, logdir, logname, c);
    try {
      processTable(fs, tableDir, log, c, majorCompact);
    } finally {
       log.close();
       // TODO: is this still right?
       BlockCache bc = new CacheConfig(c).getBlockCache();
       if (bc != null) bc.shutdown();
    }
  }
}
