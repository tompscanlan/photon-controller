/*
 * Copyright 2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.photon.controller.deployer.dcp.workflow;

import com.vmware.photon.controller.api.Deployment;
import com.vmware.photon.controller.api.ResourceList;
import com.vmware.photon.controller.api.Task;
import com.vmware.photon.controller.api.UsageTag;
import com.vmware.photon.controller.client.ApiClient;
import com.vmware.photon.controller.cloudstore.dcp.entity.DeploymentService;
import com.vmware.photon.controller.cloudstore.dcp.entity.DeploymentServiceFactory;
import com.vmware.photon.controller.common.dcp.ControlFlags;
import com.vmware.photon.controller.common.dcp.InitializationUtils;
import com.vmware.photon.controller.common.dcp.PatchUtils;
import com.vmware.photon.controller.common.dcp.QueryTaskUtils;
import com.vmware.photon.controller.common.dcp.ServiceUriPaths;
import com.vmware.photon.controller.common.dcp.ServiceUtils;
import com.vmware.photon.controller.common.dcp.TaskUtils;
import com.vmware.photon.controller.common.dcp.ValidationUtils;
import com.vmware.photon.controller.common.dcp.validation.DefaultInteger;
import com.vmware.photon.controller.common.dcp.validation.DefaultTaskState;
import com.vmware.photon.controller.common.dcp.validation.Immutable;
import com.vmware.photon.controller.common.dcp.validation.NotNull;
import com.vmware.photon.controller.common.dcp.validation.Positive;
import com.vmware.photon.controller.common.dcp.validation.WriteOnce;
import com.vmware.photon.controller.deployer.DeployerModule;
import com.vmware.photon.controller.deployer.dcp.ContainersConfig.ContainerType;
import com.vmware.photon.controller.deployer.dcp.constant.ServicePortConstants;
import com.vmware.photon.controller.deployer.dcp.task.CopyStateTaskFactoryService;
import com.vmware.photon.controller.deployer.dcp.task.CopyStateTaskService;
import com.vmware.photon.controller.deployer.dcp.task.CopyStateTriggerTaskService;
import com.vmware.photon.controller.deployer.dcp.task.MigrationStatusUpdateTriggerFactoryService;
import com.vmware.photon.controller.deployer.dcp.util.HostUtils;
import com.vmware.photon.controller.deployer.dcp.util.MiscUtils;
import com.vmware.photon.controller.deployer.deployengine.ZookeeperClient;
import com.vmware.photon.controller.deployer.deployengine.ZookeeperClientFactoryProvider;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.CompletionHandler;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import static com.google.common.base.Preconditions.checkState;

import javax.annotation.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * This class implements a DCP micro-service which performs the task of
 * migration of an existing deployment to a new deployment.
 */
public class FinalizeDeploymentMigrationWorkflowService extends StatefulService {
  /**
   * This class defines the state of a {@link FinalizeDeploymentMigrationWorkflowService} task.
   */
  public static class TaskState extends com.vmware.xenon.common.TaskState {

    /**
     * This value represents the current sub-stage for the task.
     */
    public SubStage subStage;

    /**
     * This enum represents the possible sub-states for this task.
     */
    public enum SubStage {
      PAUSE_SOURCE_SYSTEM,
      STOP_MIGRATE_TASKS,
      MIGRATE_FINAL,
      REINSTALL_AGENTS,
      RESUME_DESTINATION_SYSTEM,
    }
  }

  /**
   * This class defines the document state associated with a single
   * {@link FinalizeDeploymentMigrationWorkflowService} instance.
   */
  public static class State extends ServiceDocument {
    /**
     * This value represents the state of the task.
     */
    @DefaultTaskState(value = TaskState.TaskStage.CREATED)
    public TaskState taskState;

    /**
     * This value allows processing of post and patch operations to be
     * disabled, effectively making all service instances listeners. It is set
     * only in test scenarios.
     */
    @Immutable
    @DefaultInteger(value = 0)
    public Integer controlFlags;

    /**
     * This value represents the interval, in milliseconds, to use when polling
     * the state of a dcp task.
     */
    @Positive
    public Integer taskPollDelay;

    /**
     * This value represents the link to the source management plane in the form of http://address:port.
     */
    @NotNull
    @Immutable
    public String sourceLoadBalancerAddress;

    /**
     * This value represents the the DeploymentId on destination.
     */
    @NotNull
    @Immutable
    public String destinationDeploymentId;

    /**
     * This value represents the the DeploymentId on destination.
     */
    @WriteOnce
    public String sourceDeploymentId;

    /**
     * This value represents zookeeper quorum of the source system.
     */
    @WriteOnce
    public String sourceZookeeperQuorum;
  }

  public FinalizeDeploymentMigrationWorkflowService() {
    super(State.class);
    super.toggleOption(ServiceOption.OWNER_SELECTION, true);
    super.toggleOption(ServiceOption.PERSISTENCE, true);
    super.toggleOption(ServiceOption.REPLICATION, true);
  }

  @Override
  public void handleStart(Operation startOperation) {
    ServiceUtils.logInfo(this, "Starting service %s", getSelfLink());
    State startState = startOperation.getBody(State.class);
    InitializationUtils.initialize(startState);
    if (null == startState.taskPollDelay) {
      startState.taskPollDelay = HostUtils.getDeployerContext(this).getTaskPollDelay();
    }
    validateState(startState);

    if (TaskState.TaskStage.CREATED == startState.taskState.stage) {
      startState.taskState.stage = TaskState.TaskStage.STARTED;
      startState.taskState.subStage = TaskState.SubStage.PAUSE_SOURCE_SYSTEM;
    }

    if (startState.documentExpirationTimeMicros <= 0) {
      startState.documentExpirationTimeMicros =
          ServiceUtils.computeExpirationTime(ServiceUtils.DEFAULT_DOC_EXPIRATION_TIME);
    }

    startOperation.setBody(startState).complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(startState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping start operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == startState.taskState.stage) {
        sendStageProgressPatch(startState.taskState.stage, startState.taskState.subStage);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  @Override
  public void handlePatch(Operation patchOperation) {
    ServiceUtils.logInfo(this, "Handling patch for service %s", getSelfLink());
    State startState = getState(patchOperation);
    State patchState = patchOperation.getBody(State.class);
    validatePatchState(startState, patchState);
    State currentState = applyPatch(startState, patchState);
    validateState(currentState);
    patchOperation.complete();

    try {
      if (ControlFlags.isOperationProcessingDisabled(currentState.controlFlags)) {
        ServiceUtils.logInfo(this, "Skipping patch operation processing (disabled)");
      } else if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
        processStartedState(currentState);
      }
    } catch (Throwable t) {
      failTask(t);
    }
  }

  /**
   * This method performs the appropriate tasks while in the STARTED state.
   *
   * @param currentState Supplies the current state object.
   */
  private void processStartedState(State currentState) throws Throwable {
    switch (currentState.taskState.subStage) {
      case PAUSE_SOURCE_SYSTEM:
        pauseSourceSystem(currentState);
        break;
      case STOP_MIGRATE_TASKS:
        stopMigrateTasks(currentState);
        break;
      case MIGRATE_FINAL:
        migrateFinal(currentState);
        break;
      case REINSTALL_AGENTS:
        reinstallAgents(currentState);
        break;
      case RESUME_DESTINATION_SYSTEM:
        resumeDestinationSystem(currentState);
        break;
    }
  }

  private void validateState(State currentState) {
    ValidationUtils.validateState(currentState);
    ValidationUtils.validateTaskStage(currentState.taskState);
    validateTaskSubStage(currentState.taskState);

    if (TaskState.TaskStage.STARTED == currentState.taskState.stage) {
      switch (currentState.taskState.subStage) {
        case PAUSE_SOURCE_SYSTEM:
          break;
        case REINSTALL_AGENTS:
        case MIGRATE_FINAL:
        case RESUME_DESTINATION_SYSTEM:
          // fall through
        case STOP_MIGRATE_TASKS:
          checkState(null != currentState.sourceDeploymentId);
          break;
        default:
          throw new IllegalStateException("Unknown task sub-stage: " + currentState.taskState.subStage);
      }
    } else {
      checkState(null == currentState.taskState.subStage, "Sub-stage must be null in stages other than STARTED.");
    }
  }

  private void validateTaskSubStage(TaskState taskState) {
    switch (taskState.stage) {
      case CREATED:
        checkState(null == taskState.subStage);
        break;
      case STARTED:
        checkState(null != taskState.subStage);
        break;
      case FINISHED:
      case FAILED:
      case CANCELLED:
        checkState(null == taskState.subStage);
        break;
    }
  }

  private void validatePatchState(State currentState, State patchState) {
    ValidationUtils.validatePatch(currentState, patchState);
    ValidationUtils.validateTaskStage(patchState.taskState);
    ValidationUtils.validateTaskStageProgression(currentState.taskState, patchState.taskState);

    if (null != currentState.taskState.subStage && null != patchState.taskState.subStage) {
      checkState(patchState.taskState.subStage.ordinal() >= currentState.taskState.subStage.ordinal());
    }
  }

  private void pauseSourceSystem(final State currentState) throws Throwable {
    getDeployment(currentState, currentState.sourceLoadBalancerAddress, new FutureCallback<ResourceList<Deployment>>() {
      @Override
      public void onSuccess(@Nullable final ResourceList<Deployment> result) {
        try {
          if (result == null || result.getItems().size() != 1) {
            failTask(new IllegalStateException("No source deployment"));
            return;
          }

          Deployment deployment = result.getItems().get(0);
          pauseSourceSystem(deployment.getId(), currentState);
        } catch (Throwable throwable) {
          failTask(throwable);
        }
      }

      @Override
      public void onFailure(Throwable throwable) {
        failTask(throwable);
      }
    });
  }

  private void getDeployment(final State currentState, String endpoint, FutureCallback<ResourceList<Deployment>>
      callback)
      throws IOException {
    ApiClient client = null;
    if (endpoint != null) {
      client = HostUtils.getApiClient(this, endpoint);
    } else {
      client = HostUtils.getApiClient(this);
    }
    client.getDeploymentApi().listAllAsync(callback);
  }

  private void pauseSourceSystem(final String sourceDeploymentId, final State currentState) throws Throwable {
    ApiClient client = HostUtils.getApiClient(this, currentState.sourceLoadBalancerAddress);

    MiscUtils.getZookeeperQuorumFromSourceSystem(this, currentState.sourceLoadBalancerAddress,
        sourceDeploymentId, currentState.taskPollDelay, new FutureCallback<List<String>>() {
          @Override
          public void onSuccess(@Nullable List<String> result) {
            String zookeeperQuorum = MiscUtils.generateReplicaList(result, Integer.toString(ServicePortConstants
                .ZOOKEEPER_PORT));

            ServiceUtils.logInfo(FinalizeDeploymentMigrationWorkflowService.this,
                "Zookeeper quorum %s", zookeeperQuorum);

            try {
              client.getDeploymentApi().pauseSystemAsync(sourceDeploymentId, new FutureCallback<Task>() {
                @Override
                public void onSuccess(@Nullable Task result) {
                  moveToStopMigrateTasks(sourceDeploymentId, zookeeperQuorum);
                }

                @Override
                public void onFailure(Throwable throwable) {
                  if (throwable.getMessage().contains("SystemPaused")) {
                    moveToStopMigrateTasks(sourceDeploymentId, zookeeperQuorum);
                  } else {
                    failTask(throwable);
                  }
                }
              });

            } catch (Throwable t) {
              failTask(t);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            failTask(t);
          }
        });
  }

  private void moveToStopMigrateTasks(final String sourceDeploymentId, String zookeeperQuorum) {
    HostUtils.getListeningExecutorService(this).execute(() -> {
      try {
        disbaleHouseKeeperOnSource(sourceDeploymentId, zookeeperQuorum);
      } catch (Throwable t) {
        failTask(t);
        return;
      }
      State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.STOP_MIGRATE_TASKS,
          null);
      patchState.sourceDeploymentId = sourceDeploymentId;
      patchState.sourceZookeeperQuorum = zookeeperQuorum;
      TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, patchState);
    });
  }

  // Since we will need to ensure that the old management plane is not actively
  // deleting images, we need to shutdown house keeper.
  // This should be deprecated soon as fully pausing the sytem will take care
  // of that in the future.
  @Deprecated
  private void disbaleHouseKeeperOnSource(final String sourceDeploymentId, String sourceZookeeperQuorum) {
    ZookeeperClient zookeeperClient
      = ((ZookeeperClientFactoryProvider) getHost()).getZookeeperServerSetFactoryBuilder().create();
    Set<InetSocketAddress> sourceServers
      = zookeeperClient.getServers(sourceZookeeperQuorum, DeployerModule.CLOUDSTORE_SERVICE_NAME);

    for (InetSocketAddress address : sourceServers) {
      HostUtils.getDockerProvisionerFactory(this)
        .create(address.getAddress().getHostAddress())
        .stopContainerMatching(ContainerType.Housekeeper.name());
    }
  }

  private void stopMigrateTasks(State currentState) {
    // stop the copy-state trigger
    Operation copyStateTaskTriggerQuery = generateQueryCopyStateTaskTriggerQuery();
    copyStateTaskTriggerQuery
      .setCompletion((op, t) -> {
        if (t != null) {
          failTask(t);
          return;
        }

        List<CopyStateTriggerTaskService.State> documents = QueryTaskUtils
          .getBroadcastQueryDocuments(CopyStateTriggerTaskService.State.class, op);
        List<Operation> operations = documents.stream()
          .map((state) -> {
            CopyStateTriggerTaskService.State patchState = new CopyStateTriggerTaskService.State();
            patchState.executionState = CopyStateTriggerTaskService.ExecutionState.STOPPED;
            Operation patch = Operation
                .createPatch(UriUtils.buildUri(getHost(), state.documentSelfLink))
                .setBody(patchState);
            return patch;
          })
          .collect(Collectors.toList());

        if (operations.isEmpty()) {
            State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.MIGRATE_FINAL, null);
            TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, patchState);
        } else {
            OperationJoin.create(operations)
              .setCompletion((ops, ts) -> {
                if (ts != null && !ts.isEmpty()) {
                  failTask(ts.values());
                  return;
                }

                waitUntilCopyStateTasksFinished((operation, throwable) -> {
                  if (throwable != null) {
                    failTask(throwable);
                    return;
                  }
                  State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.MIGRATE_FINAL, null);
                  TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, patchState);
                }, currentState);
              })
              .sendWith(this);
        }
      })
      .sendWith(this);

  }

  private void waitUntilCopyStateTasksFinished(CompletionHandler handler, State currentState) {
    // wait until all the copy-state services are done
    generateQueryCopyStateTaskQuery()
      .setCompletion((op, t) -> {
        if (t != null) {
          handler.handle(op, t);
          return;
        }
        List<CopyStateTaskService.State> documents =
            QueryTaskUtils.getBroadcastQueryDocuments(CopyStateTaskService.State.class, op);
        List<CopyStateTaskService.State> runningServices = documents.stream()
            .filter((d) -> d.taskState.stage == TaskStage.CREATED && d.taskState.stage == TaskStage.STARTED)
            .collect(Collectors.toList());
        if (runningServices.isEmpty()) {
          handler.handle(op,  t);
          return;
        }
        getHost().schedule(
            () -> waitUntilCopyStateTasksFinished(handler, currentState),
            currentState.taskPollDelay,
            TimeUnit.SECONDS);
      })
      .sendWith(this);
  }

  private Operation generateQueryCopyStateTaskTriggerQuery() {
    QueryTask queryTask = QueryTask.Builder.createDirectTask()
        .setQuery(QueryTask.Query.Builder.create()
            .addKindFieldClause(CopyStateTriggerTaskService.State.class)
            .build())
        .addOption(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT)
        .build();
    return Operation
        .createPost(UriUtils.buildBroadcastRequestUri(
            UriUtils.buildUri(getHost(), ServiceUriPaths.CORE_LOCAL_QUERY_TASKS),
            ServiceUriPaths.DEFAULT_NODE_SELECTOR))
        .setBody(queryTask);
  }

  private Operation generateQueryCopyStateTaskQuery() {
    QueryTask.Query typeClause = new QueryTask.Query()
        .setTermPropertyName(ServiceDocument.FIELD_NAME_KIND)
        .setTermMatchValue(Utils.buildKind(CopyStateTaskService.State.class));
    QueryTask.QuerySpecification querySpecification = new QueryTask.QuerySpecification();
    querySpecification.query
      .addBooleanClause(buildExcludeQuery("taskState.stage", TaskState.TaskStage.CANCELLED.name()));
    querySpecification.query
      .addBooleanClause(buildExcludeQuery("taskState.stage", TaskState.TaskStage.FAILED.name()));
    querySpecification.query
      .addBooleanClause(typeClause);
    querySpecification.options = EnumSet.of(QueryTask.QuerySpecification.QueryOption.EXPAND_CONTENT);
    return Operation
        .createPost(UriUtils.buildBroadcastRequestUri(
            UriUtils.buildUri(getHost(), ServiceUriPaths.CORE_LOCAL_QUERY_TASKS),
            ServiceUriPaths.DEFAULT_NODE_SELECTOR))
        .setBody(QueryTask.create(querySpecification).setDirect(true));
  }

  private QueryTask.Query buildExcludeQuery(String property, String value) {
    QueryTask.Query excludeStarted = buildBaseQuery(property, value);
    excludeStarted.occurance = QueryTask.Query.Occurance.MUST_NOT_OCCUR;
    return excludeStarted;
  }

  private QueryTask.Query buildBaseQuery(String property, String value) {
    return new QueryTask.Query()
        .setTermPropertyName(property)
        .setTermMatchValue(value);
  }

  private void reinstallAgents(State currentState) {

    sendRequest(
        HostUtils.getCloudStoreHelper(this)
            .createGet(DeploymentServiceFactory.SELF_LINK + "/" + currentState.destinationDeploymentId)
            .setCompletion(
                (completedOp, failure) -> {
                  if (null != failure) {
                    failTask(failure);
                    return;
                  }

                  try {
                    reinstallAgents(currentState, completedOp.getBody(DeploymentService.State.class));
                  } catch (Throwable t) {
                    failTask(t);
                  }
                }
            ));
  }

  private void reinstallAgents(State currentState, DeploymentService.State deploymentState) {

    FutureCallback<BulkProvisionHostsWorkflowService.State> provisionCallback =
        new FutureCallback<BulkProvisionHostsWorkflowService.State>() {
          @Override
          public void onSuccess(@Nullable BulkProvisionHostsWorkflowService.State result) {
            switch (result.taskState.stage) {
              case FINISHED:
                State patchState = buildPatch(TaskState.TaskStage.STARTED, TaskState.SubStage.RESUME_DESTINATION_SYSTEM
                    , null);
                TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, patchState);
                break;
              case FAILED:
                State failPatchState = buildPatch(TaskState.TaskStage.FAILED, null, null);
                failPatchState.taskState.failure = result.taskState.failure;
                TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, failPatchState);
                break;
              case CANCELLED:
                TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, buildPatch(TaskState.TaskStage
                    .CANCELLED, null, null));
                break;
              default:
                failPatchState = buildPatch(
                    TaskState.TaskStage.FAILED,
                    null,
                    new RuntimeException("Unexpected stage [" + result.taskState.stage + "]"));
                TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, failPatchState);
                break;
            }
          }

          @Override
          public void onFailure(Throwable t) {
            failTask(t);
          }
        };

    BulkProvisionHostsWorkflowService.State startState = new BulkProvisionHostsWorkflowService.State();
    startState.taskState = new BulkProvisionHostsWorkflowService.TaskState();
    startState.taskState.stage = com.vmware.xenon.common.TaskState.TaskStage.STARTED;
    startState.taskState.subStage = BulkProvisionHostsWorkflowService.TaskState.SubStage.UPLOAD_VIB;
    startState.deploymentServiceLink = deploymentState.documentSelfLink;
    startState.chairmanServerList = deploymentState.chairmanServerList;
    startState.usageTag = UsageTag.CLOUD.name();
    startState.querySpecification = MiscUtils.generateHostQuerySpecification(null, null);
    startState.taskPollDelay = currentState.taskPollDelay;

    TaskUtils.startTaskAsync(
        FinalizeDeploymentMigrationWorkflowService.this,
        BulkProvisionHostsWorkflowFactoryService.SELF_LINK,
        startState,
        (state) -> TaskUtils.finalTaskStages.contains(state.taskState.stage),
        BulkProvisionHostsWorkflowService.State.class,
        currentState.taskPollDelay,
        provisionCallback);
  }

  private void migrateFinal(State currentState) {
    generateQueryCopyStateTaskQuery()
      .setCompletion((o, t) -> {
        if (t != null) {
          failTask(t);
          return;
        }
        List<CopyStateTaskService.State> queryDocuments =
            QueryTaskUtils.getBroadcastQueryDocuments(CopyStateTaskService.State.class, o);

        Map<String, Long> lastUpdateTimes = new HashMap<>();
        Map<String, String> factoryOrigin = new HashMap<>();
        queryDocuments.stream().forEach((state) -> {
          long currentLatestUpdateTime = lastUpdateTimes.getOrDefault(state.sourceFactoryLink, 0L);
          Long latestUpdateTime = Math.max(state.lastDocumentUpdateTimeEpoc, currentLatestUpdateTime);
          lastUpdateTimes.put(state.sourceFactoryLink, latestUpdateTime);
          factoryOrigin.put(state.sourceFactoryLink, state.sourceIp);
        });

        migrateFinal(currentState, lastUpdateTimes, factoryOrigin);
      })
      .sendWith(this);
  }

  private void migrateFinal(State currentState, Map<String, Long> lastUpdateTimes, Map<String, String> factoryOrigin) {
    ZookeeperClient zookeeperClient
        = ((ZookeeperClientFactoryProvider) getHost()).getZookeeperServerSetFactoryBuilder().create();
    Set<InetSocketAddress> destinationServers = zookeeperClient.getServers(
        HostUtils.getDeployerContext(this).getZookeeperQuorum(),
        DeployerModule.CLOUDSTORE_SERVICE_NAME);
    Set<InetSocketAddress> sourceServers
        = zookeeperClient.getServers(currentState.sourceZookeeperQuorum, DeployerModule.CLOUDSTORE_SERVICE_NAME);

    Set<Map.Entry<String, String>> factoryMap = HostUtils.getDeployerContext(this).getFactoryLinkMapEntries();

    OperationJoin.create(
        factoryMap.stream()
        .map(entry -> {
          String sourceFactory = entry.getKey();
          if (!sourceFactory.endsWith("/")) {
            sourceFactory += "/";
          }
          CopyStateTaskService.State startState
            = MiscUtils.createCopyStateStartState(sourceServers, destinationServers, entry.getValue(), sourceFactory);
          startState.queryDocumentsChangedSinceEpoc = lastUpdateTimes.getOrDefault(sourceFactory, 0L);
          // keep the original source since the time stamp are local to the source server
          startState.sourceIp = factoryOrigin.getOrDefault(sourceFactory, startState.sourceIp);
          startState.performHostTransformation = Boolean.TRUE;
          return Operation
            .createPost(this, CopyStateTaskFactoryService.SELF_LINK)
            .setBody(startState);
        }).collect(Collectors.toList()))
      .setCompletion((es, ts) -> {
        if (ts != null && !ts.isEmpty()) {
          failTask(ts.values());
          return;
        }
        waitUntilCopyStateTasksFinished((operation, throwable) -> {
          if (throwable != null) {
            failTask(throwable);
            return;
          }
          stopMigrationUpdateService(currentState);
        }, currentState);
      })
      .sendWith(this);
  }

  private void stopMigrationUpdateService(State currentState) {
    Operation delete = Operation.createDelete(this,
        MigrationStatusUpdateTriggerFactoryService.SELF_LINK + "/" + currentState.destinationDeploymentId)
        .setCompletion((o, t) -> {
          if (t != null) {
            failTask(t);
            return;
          }
          State patchState = buildPatch(
              TaskState.TaskStage.STARTED,
              TaskState.SubStage.REINSTALL_AGENTS,
              null);
          TaskUtils.sendSelfPatch(FinalizeDeploymentMigrationWorkflowService.this, patchState);
        });
    sendRequest(delete);
  }

  private void resumeDestinationSystem(final State currentState) throws Throwable {
    ApiClient client = HostUtils.getApiClient(this);

    FutureCallback<Task> callback = new FutureCallback<Task>() {
      @Override
      public void onSuccess(@Nullable Task result) {
        sendStageProgressPatch(TaskState.TaskStage.FINISHED, null);
      }

      @Override
      public void onFailure(Throwable throwable) {
        failTask(throwable);
      }
    };

    client.getDeploymentApi().resumeSystemAsync(currentState.destinationDeploymentId, callback);
  }

  private State applyPatch(State currentState, State patchState) {
    if (patchState.taskState.stage != currentState.taskState.stage
        || patchState.taskState.subStage != currentState.taskState.subStage) {
      ServiceUtils.logInfo(this, "Moving to stage %s:%s", patchState.taskState.stage, patchState.taskState.subStage);
      currentState.taskState = patchState.taskState;
    }

    PatchUtils.patchState(currentState, patchState);
    return currentState;
  }

  private void sendStageProgressPatch(TaskState.TaskStage patchStage, @Nullable TaskState.SubStage patchSubStage) {
    ServiceUtils.logInfo(this, "Sending self-patch to stage %s:%s", patchStage, patchSubStage);
    TaskUtils.sendSelfPatch(this, buildPatch(patchStage, patchSubStage, null));
  }

  private void failTask(Throwable t) {
    ServiceUtils.logSevere(this, t);
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, t));
  }

  private void failTask(Collection<Throwable> failures) {
    failures.forEach((throwable) -> ServiceUtils.logSevere(this, throwable));
    TaskUtils.sendSelfPatch(this, buildPatch(TaskState.TaskStage.FAILED, null, failures.iterator().next()));
  }

  @VisibleForTesting
  protected static State buildPatch(
      TaskState.TaskStage patchStage,
      @Nullable TaskState.SubStage patchSubStage,
      @Nullable Throwable t) {

    State patchState = new State();
    patchState.taskState = new TaskState();
    patchState.taskState.stage = patchStage;
    patchState.taskState.subStage = patchSubStage;

    if (null != t) {
      patchState.taskState.failure = Utils.toServiceErrorResponse(t);
    }

    return patchState;
  }
}
