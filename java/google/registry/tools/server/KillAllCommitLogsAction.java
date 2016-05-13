// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.tools.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Lists.partition;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.request.Action.Method.POST;
import static com.google.domain.registry.util.PipelineUtils.createJobPath;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.inputs.InMemoryInput;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.domain.registry.config.RegistryEnvironment;
import com.google.domain.registry.mapreduce.MapreduceAction;
import com.google.domain.registry.mapreduce.MapreduceRunner;
import com.google.domain.registry.model.ofy.CommitLogBucket;
import com.google.domain.registry.model.ofy.CommitLogCheckpointRoot;
import com.google.domain.registry.request.Action;
import com.google.domain.registry.request.Response;

import com.googlecode.objectify.Key;

import java.util.Arrays;

import javax.inject.Inject;

/** Deletes all commit logs in datastore. */
@Action(path = "/_dr/task/killAllCommitLogs", method = POST)
public class KillAllCommitLogsAction implements MapreduceAction {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject KillAllCommitLogsAction() {}

  @Override
  public final void run() {
    checkArgument( // safety
        RegistryEnvironment.get() == RegistryEnvironment.CRASH
            || RegistryEnvironment.get() == RegistryEnvironment.UNITTEST,
        "DO NOT RUN ANYWHERE ELSE EXCEPT CRASH OR TESTS.");
    response.sendJavaScriptRedirect(createJobPath(mrRunner
        .setJobName("Delete all commit logs and checkpoints")
        .setModuleName("tools")
        .runMapreduce(
            new KillAllCommitLogsMapper(),
            new KillAllEntitiesReducer(),
            // Create a in-memory input, assigning each bucket to its own shard for maximum
            // parallelization, with one extra shard for the CommitLogCheckpointRoot.
            ImmutableList.of(
                new InMemoryInput<>(
                    partition(
                        FluentIterable
                            .from(Arrays.<Key<?>>asList(CommitLogCheckpointRoot.getKey()))
                            .append(CommitLogBucket.getAllBucketKeys())
                            .toList(),
                    1))))));
  }

  /**
   * Mapper to delete a {@link CommitLogBucket} or {@link CommitLogCheckpointRoot} and any commit
   * logs or checkpoints that descend from it.
   *
   * <p>This will delete:
   * <ul>
   *   <li>{@link CommitLogBucket}
   *   <li>{@code CommitLogCheckpoint}
   *   <li>{@link CommitLogCheckpointRoot}
   *   <li>{@code CommitLogManifest}
   *   <li>{@code CommitLogMutation}
   * </ul>
   */
  static class KillAllCommitLogsMapper extends Mapper<Key<?>, Key<?>, Key<?>> {

    private static final long serialVersionUID = 1504266335352952033L;

    @Override
    public void map(Key<?> bucketOrRoot) {
      for (Key<Object> key : ofy().load().ancestor(bucketOrRoot).keys()) {
        emit(bucketOrRoot, key);
        getContext().incrementCounter("entities emitted");
        getContext().incrementCounter(String.format("%s emitted", key.getKind()));
     }
    }
  }
}
