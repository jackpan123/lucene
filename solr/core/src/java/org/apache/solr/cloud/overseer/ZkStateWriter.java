/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud.overseer;

import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.solr.cloud.Stats;
import org.apache.solr.common.AlreadyClosedException;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.Utils;
import org.apache.solr.util.BoundedTreeSet;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.singletonMap;

public class ZkStateWriter {
  // private static final long MAX_FLUSH_INTERVAL = TimeUnit.NANOSECONDS.convert(Overseer.STATE_UPDATE_DELAY, TimeUnit.MILLISECONDS);

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final ZkStateReader reader;

  /**
   * Represents a no-op {@link ZkWriteCommand} which will result in no modification to cluster state
   */

  protected volatile Stats stats;

  AtomicReference<Exception> lastFailedException = new AtomicReference<>();
  private final Map<String,Integer> trackVersions = new ConcurrentHashMap<>();

  Map<String,DocCollection> failedUpdates = new ConcurrentHashMap<>();

  private volatile ClusterState cs;
  private boolean dirty;
  private Set<String> collectionsToWrite = new HashSet<>();

  public ZkStateWriter(ZkStateReader zkStateReader, Stats stats) {
    assert zkStateReader != null;

    this.reader = zkStateReader;
    this.stats = stats;

    zkStateReader.forciblyRefreshAllClusterStateSlow();
    cs = zkStateReader.getClusterState();
  }

  public synchronized void enqueueUpdate(ClusterState clusterState, boolean stateUpdate) throws Exception {

    if (log.isDebugEnabled()) log.debug("enqueue update stateUpdate={}", stateUpdate);

    AtomicBoolean changed = new AtomicBoolean();
    if (clusterState == null) {
      throw new NullPointerException("clusterState cannot be null");
    }
    if (!stateUpdate) {
      changed.set(true);
      clusterState.forEachCollection(collection -> {
        DocCollection currentCollection = cs.getCollectionOrNull(collection.getName());

        for (Slice slice : collection) {
          if (currentCollection != null) {
            Slice currentSlice = currentCollection.getSlice(slice.getName());
            if (currentSlice != null) {
              slice.setState(currentSlice.getState());
              slice.setLeader(currentSlice.getLeader());
            }
          }

          for (Replica replica : slice) {
            if (currentCollection != null) {
              Replica currentReplica = currentCollection.getReplica(replica.getName());
              if (currentReplica != null) {
                replica.setState(currentReplica.getState());
              }
            }
            Object removed = replica.getProperties().remove("numShards");
          }
        }
      });


      collectionsToWrite.addAll(clusterState.getCollectionsMap().keySet());
      Collection<DocCollection> collections = cs.getCollectionsMap().values();
      for (DocCollection collection : collections) {
        if (clusterState.getCollectionOrNull(collection.getName()) == null) {
          clusterState = clusterState.copyWith(collection.getName(), collection);
        }
      }

      this.cs = clusterState;
    } else {
      clusterState.forEachCollection(newCollection -> {

        DocCollection currentCollection = cs.getCollectionOrNull(newCollection.getName());
        if (currentCollection == null) {
          log.error("Could not update state for non existing collection {}", newCollection.getName());
          return;
        }
        for (Slice slice : newCollection) {
          Slice currentSlice = currentCollection.getSlice(slice.getName());
          if (currentSlice != null) {
            if (log.isDebugEnabled()) log.debug("set slice state to {} {} leader={}", slice.getName(), slice.getState(), slice.getLeader());
            Replica leader = slice.getLeader();
            if (leader != null) {
              currentSlice.setState(slice.getState());
              currentSlice.setLeader(slice.getLeader());
              currentSlice.getLeader().getProperties().put("leader", "true");
              changed.set(true);
            }
          }
          for (Replica replica : slice) {
            Replica currentReplica = currentCollection.getReplica(replica.getName());
            if (currentReplica != null) {
              if (log.isDebugEnabled()) log.debug("set replica state to {} isLeader={}", replica.getState(), replica.getProperty("leader"));
              currentReplica.setState(replica.getState());
              String leader = replica.getProperty("leader");
              if (leader != null) {
                currentReplica.getProperties().put("leader", leader);
              }
// nocommit
//              else if (leader == null) {
//                currentReplica.getProperties().remove("leader");
//              }

              if (slice.getLeader() != null &&  slice.getLeader().getName().equals(replica.getName())) {
                currentReplica.getProperties().put("leader", "true");
              }

              Replica thereplica = cs.getCollectionOrNull(newCollection.getName()).getReplica(replica.getName());
              if (log.isDebugEnabled()) log.debug("Check states nreplica={} ceplica={}", replica.getState(), thereplica.getState());

              if (replica.getState() == Replica.State.ACTIVE) {
                if (log.isDebugEnabled()) log.debug("Setting replica to active state leader={} state={} col={}", leader, cs, currentCollection);
              }

              changed.set(true); // nocommit - only if really changed
            }

          }
        }
      });

      cs.forEachCollection(collection -> {
        Object removed = collection.getProperties().remove("replicationFactor");
        if (removed != null) {
          changed.set(true); // nocommit - only if really changed
        }
        removed = collection.getProperties().remove("pullReplicas");
        if (removed != null) {
          changed.set(true); // nocommit - only if really changed
        }
        removed = collection.getProperties().remove("maxShardsPerNode");
        if (removed != null) {
          changed.set(true); // nocommit - only if really changed
        }
        removed = collection.getProperties().remove("nrtReplicas");
        if (removed != null) {
          changed.set(true); // nocommit - only if really changed
        }
        removed = collection.getProperties().remove("tlogReplicas");
        if (removed != null) {
          changed.set(true); // nocommit - only if really changed
        }

        for (Slice slice : collection) {
          Replica leader = slice.getLeader();
          if (leader != null && leader.getState() != Replica.State.ACTIVE) {
            slice.setLeader(null);
            leader.getProperties().remove("leader");
            changed.set(true);
          }

          for (Replica replica : slice) {
            String isLeader = replica.getProperty("leader");
            if (log.isDebugEnabled()) log.debug("isleader={} slice={} state={} sliceLeader={}", isLeader, slice.getName(), slice.getState(), slice.getLeader());
            if (Boolean.parseBoolean(isLeader) && replica.getState() != Replica.State.ACTIVE) {
              if (log.isDebugEnabled()) log.debug("clear leader isleader={} slice={} state={} sliceLeader={}", isLeader, slice.getName(), slice.getState(), slice.getLeader());
              replica.getProperties().remove("leader");
              changed.set(true); // nocommit - only if really changed
            }

            removed = replica.getProperties().remove("numShards");
            if (removed != null) {
              changed.set(true); // nocommit - only if really changed
            }
            removed = replica.getProperties().remove("base_url");
            if (removed != null) {
              changed.set(true); // nocommit - only if really changed
            }

          }
        }
      });
      collectionsToWrite.addAll(clusterState.getCollectionsMap().keySet());
    }

    if (stateUpdate) {
//      log.error("isStateUpdate, final cs: {}", this.cs);
//      log.error("isStateUpdate, submitted state: {}", clusterState);
      if (!changed.get()) {
        log.warn("Published state that changed nothing");
        return;
      }
    }
    dirty = true;
  }

  public Integer lastWrittenVersion(String collection) {
    return trackVersions.get(collection);
  }

  /**
   * Writes all pending updates to ZooKeeper and returns the modified cluster state
   *
   */
  public synchronized void writePendingUpdates() {
    if (!dirty) {
      return;
    }

    if (log.isDebugEnabled()) {
      log.debug("writePendingUpdates {}", cs);
    }

    if (failedUpdates.size() > 0) {
      log.warn("Some collection updates failed {} logging last exception", failedUpdates, lastFailedException); // nocommit expand
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, lastFailedException.get());
    }

    // wait to see our last publish version has propagated
    cs.forEachCollection(collection -> {
      if (collectionsToWrite.contains(collection.getName())) {
        Integer v = null;
        try {
          //System.out.println("waiting to see state " + prevVersion);
          v = trackVersions.get(collection.getName());
          if (v == null) v = 0;
          if (v == 0) return;
          Integer version = v;
          try {
            log.debug("wait to see last published version for collection {} {}", collection.getName(), v);
            reader.waitForState(collection.getName(), 5, TimeUnit.SECONDS, (l, col) -> {
              if (col == null) {
                return true;
              }
              //                          if (col != null) {
              //                            log.info("the version " + col.getZNodeVersion());
              //                          }
              if (col != null && col.getZNodeVersion() >= version) {
                if (log.isDebugEnabled()) log.debug("Waited for ver: {}", col.getZNodeVersion() + 1);
                // System.out.println("found the version");
                return true;
              }
              return false;
            });
          } catch (InterruptedException e) {
            ParWork.propagateInterrupt(e);
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
          }
        } catch (TimeoutException e) {
          log.warn("Timeout waiting to see written cluster state come back " + v);
        }
      }

    });


    cs.forEachCollection(collection -> {
      if (collectionsToWrite.contains(collection.getName())) {
        String name = collection.getName();
        String path = ZkStateReader.getCollectionPath(collection.getName());
        if (log.isDebugEnabled()) log.debug("process {}", collection);
        Stat stat = new Stat();
        boolean success = false;
        try {

          byte[] data = Utils.toJSON(singletonMap(name, collection));

          if (log.isDebugEnabled()) log.debug("Write state.json prevVersion={} bytes={} col={}", collection.getZNodeVersion(), data.length, collection);

          try {
            int version = collection.getZNodeVersion();
            Integer v = trackVersions.get(collection.getName());
            if (v != null) {
              version = v;
            }

            reader.getZkClient().setData(path, data, version == 0 ? -1 : version, true);

            trackVersions.put(collection.getName(), version + 1);
          } catch (KeeperException.NoNodeException e) {
            if (log.isDebugEnabled()) log.debug("No node found for state.json", e);
            trackVersions.remove(collection.getName());
            // likely deleted
          } catch (KeeperException.BadVersionException bve) {
            lastFailedException.set(bve);
            failedUpdates.put(collection.getName(), collection);
            stat = reader.getZkClient().exists(path, null);
            // this is a tragic error, we must disallow usage of this instance
            log.warn("Tried to update the cluster state using version={} but we where rejected, found {}", collection.getZNodeVersion(), stat.getVersion(), bve);
          }
          if (log.isDebugEnabled()) log.debug("Set version for local collection {} to {}", collection.getName(), collection.getZNodeVersion() + 1);
        } catch (InterruptedException | AlreadyClosedException e) {
          log.info("We have been closed or one of our resources has, bailing {}", e.getClass().getSimpleName() + ":" + e.getMessage());
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
        } catch (KeeperException.SessionExpiredException e) {
          log.error("", e);
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
        } catch (Exception e) {
          log.error("Failed processing update=" + collection, e);
        }
      }
    });

    dirty = false;
    collectionsToWrite.clear();

    // nocommit - harden against failures and exceptions

    //    if (log.isDebugEnabled()) {
    //      log.debug("writePendingUpdates() - end - New Cluster State is: {}", newClusterState);
    //    }

  }

  public synchronized ClusterState getClusterstate(boolean stateUpdate) {
    return cs;
  }

}

