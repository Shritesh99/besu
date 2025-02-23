/*
 * Copyright contributors to Hyperledger Besu
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.sync.snapsync.request;

import static org.hyperledger.besu.ethereum.eth.sync.snapsync.RangeManager.MAX_RANGE;
import static org.hyperledger.besu.ethereum.eth.sync.snapsync.RangeManager.MIN_RANGE;
import static org.hyperledger.besu.ethereum.eth.sync.snapsync.RangeManager.findNewBeginElementInRange;
import static org.hyperledger.besu.ethereum.eth.sync.snapsync.RangeManager.getRangeCount;
import static org.hyperledger.besu.ethereum.eth.sync.snapsync.RequestType.STORAGE_RANGE;
import static org.hyperledger.besu.ethereum.eth.sync.snapsync.StackTrie.FlatDatabaseUpdater.noop;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.RangeManager;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapSyncConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapSyncProcessState;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapWorldDownloadState;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.StackTrie;
import org.hyperledger.besu.ethereum.proof.WorldStateProofProvider;
import org.hyperledger.besu.ethereum.trie.CompactEncoding;
import org.hyperledger.besu.ethereum.trie.NodeUpdater;
import org.hyperledger.besu.ethereum.worldstate.FlatDbMode;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage.Updater;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;
import kotlin.collections.ArrayDeque;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.rlp.RLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Returns a list of storages and the merkle proofs of an entire range */
public class StorageRangeDataRequest extends SnapDataRequest {

  private static final Logger LOG = LoggerFactory.getLogger(StorageRangeDataRequest.class);

  private final Hash accountHash;
  private final Bytes32 storageRoot;
  private final Bytes32 startKeyHash;
  private final Bytes32 endKeyHash;

  private StackTrie stackTrie;
  private Optional<Boolean> isProofValid;

  protected StorageRangeDataRequest(
      final Hash rootHash,
      final Bytes32 accountHash,
      final Bytes32 storageRoot,
      final Bytes32 startKeyHash,
      final Bytes32 endKeyHash) {
    super(STORAGE_RANGE, rootHash);
    this.accountHash = Hash.wrap(accountHash);
    this.storageRoot = storageRoot;
    this.startKeyHash = startKeyHash;
    this.endKeyHash = endKeyHash;
    this.isProofValid = Optional.empty();
    addStackTrie(Optional.empty());
    LOG.trace(
        "create get storage range data request for account {} with root hash={} from {} to {}",
        accountHash,
        rootHash,
        startKeyHash,
        endKeyHash);
  }

  @Override
  protected int doPersist(
      final WorldStateStorage worldStateStorage,
      final Updater updater,
      final SnapWorldDownloadState downloadState,
      final SnapSyncProcessState snapSyncState,
      final SnapSyncConfiguration snapSyncConfiguration) {

    // search incomplete nodes in the range
    final AtomicInteger nbNodesSaved = new AtomicInteger();
    final NodeUpdater nodeUpdater =
        (location, hash, value) -> {
          updater.putAccountStorageTrieNode(accountHash, location, hash, value);
        };

    StackTrie.FlatDatabaseUpdater flatDatabaseUpdater = noop();
    if (worldStateStorage.getFlatDbMode().equals(FlatDbMode.FULL)) {
      // we have a flat DB only with Bonsai
      flatDatabaseUpdater =
          (key, value) ->
              ((BonsaiWorldStateKeyValueStorage.Updater) updater)
                  .putStorageValueBySlotHash(
                      accountHash, Hash.wrap(key), Bytes32.leftPad(RLP.decodeValue(value)));
    }

    stackTrie.commit(flatDatabaseUpdater, nodeUpdater);

    downloadState.getMetricsManager().notifySlotsDownloaded(stackTrie.getElementsCount().get());

    return nbNodesSaved.get();
  }

  public void addResponse(
      final SnapWorldDownloadState downloadState,
      final WorldStateProofProvider worldStateProofProvider,
      final TreeMap<Bytes32, Bytes> slots,
      final ArrayDeque<Bytes> proofs) {
    if (!slots.isEmpty() || !proofs.isEmpty()) {
      if (!worldStateProofProvider.isValidRangeProof(
          startKeyHash, endKeyHash, storageRoot, proofs, slots)) {
        // If the proof is invalid, it means that the storage will be a mix of several blocks.
        // Therefore, it will be necessary to heal the account's storage subsequently
        downloadState.addAccountToHealingList(CompactEncoding.bytesToPath(accountHash));
        // We will request the new storage root of the account because it is apparently no longer
        // valid with the new pivot block.
        downloadState.enqueueRequest(
            createAccountDataRequest(
                getRootHash(), Hash.wrap(accountHash), startKeyHash, endKeyHash));
        isProofValid = Optional.of(false);
      } else {
        stackTrie.addElement(startKeyHash, proofs, slots);
        isProofValid = Optional.of(true);
      }
    }
  }

  @Override
  public boolean isResponseReceived() {
    return isProofValid.isPresent();
  }

  @Override
  public boolean isExpired(final SnapSyncProcessState snapSyncState) {
    return snapSyncState.isExpired(this);
  }

  @Override
  public Stream<SnapDataRequest> getChildRequests(
      final SnapWorldDownloadState downloadState,
      final WorldStateStorage worldStateStorage,
      final SnapSyncProcessState snapSyncState) {
    final List<SnapDataRequest> childRequests = new ArrayList<>();

    if (!isProofValid.orElse(false)) {
      return Stream.empty();
    }

    final StackTrie.TaskElement taskElement = stackTrie.getElement(startKeyHash);

    findNewBeginElementInRange(storageRoot, taskElement.proofs(), taskElement.keys(), endKeyHash)
        .ifPresent(
            missingRightElement -> {
              final int nbRanges = getRangeCount(startKeyHash, endKeyHash, taskElement.keys());
              RangeManager.generateRanges(missingRightElement, endKeyHash, nbRanges)
                  .forEach(
                      (key, value) -> {
                        final StorageRangeDataRequest storageRangeDataRequest =
                            createStorageRangeDataRequest(
                                getRootHash(), accountHash, storageRoot, key, value);
                        storageRangeDataRequest.addStackTrie(Optional.of(stackTrie));
                        childRequests.add(storageRangeDataRequest);
                      });
              if (startKeyHash.equals(MIN_RANGE) && endKeyHash.equals(MAX_RANGE)) {
                // need to heal this account storage
                downloadState.addAccountToHealingList(CompactEncoding.bytesToPath(accountHash));
              }
            });

    return childRequests.stream();
  }

  public Bytes32 getAccountHash() {
    return accountHash;
  }

  public Bytes32 getStorageRoot() {
    return storageRoot;
  }

  public TreeMap<Bytes32, Bytes> getSlots() {
    return stackTrie.getElement(startKeyHash).keys();
  }

  public Bytes32 getStartKeyHash() {
    return startKeyHash;
  }

  public Bytes32 getEndKeyHash() {
    return endKeyHash;
  }

  @VisibleForTesting
  public void setProofValid(final boolean isProofValid) {
    this.isProofValid = Optional.of(isProofValid);
  }

  public void addStackTrie(final Optional<StackTrie> maybeStackTrie) {
    stackTrie =
        maybeStackTrie
            .filter(StackTrie::addSegment)
            .orElse(new StackTrie(Hash.wrap(getStorageRoot()), 1, 3, startKeyHash));
  }
}
