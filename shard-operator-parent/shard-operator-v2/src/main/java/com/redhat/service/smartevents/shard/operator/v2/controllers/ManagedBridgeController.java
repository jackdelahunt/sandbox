package com.redhat.service.smartevents.shard.operator.v2.controllers;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.service.smartevents.infra.core.metrics.MetricsOperation;
import com.redhat.service.smartevents.shard.operator.core.metrics.OperatorMetricsService;
import com.redhat.service.smartevents.shard.operator.core.networking.NetworkingService;
import com.redhat.service.smartevents.shard.operator.core.resources.ConditionTypeConstants;
import com.redhat.service.smartevents.shard.operator.core.resources.knative.KnativeBroker;
import com.redhat.service.smartevents.shard.operator.core.utils.EventSourceFactory;
import com.redhat.service.smartevents.shard.operator.core.utils.LabelsBuilder;
import com.redhat.service.smartevents.shard.operator.v2.ManagedBridgeService;
import com.redhat.service.smartevents.shard.operator.v2.resources.ManagedBridge;
import com.redhat.service.smartevents.shard.operator.v2.resources.ManagedBridgeStatus;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusHandler;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.RetryInfo;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;

@ApplicationScoped
@ControllerConfiguration(labelSelector = LabelsBuilder.V2_RECONCILER_LABEL_SELECTOR)
public class ManagedBridgeController implements Reconciler<ManagedBridge>,
        EventSourceInitializer<ManagedBridge>,
        ErrorStatusHandler<ManagedBridge> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedBridgeController.class);

    @ConfigProperty(name = "event-bridge.managed-bridge.poll-interval.milliseconds")
    int reconcileIntervalSeconds;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    NetworkingService networkingService;

    @Inject
    ManagedBridgeService managedBridgeService;

    @Inject
    OperatorMetricsService metricsService;

    @Override
    public UpdateControl<ManagedBridge> reconcile(ManagedBridge managedBridge, Context context) {

        LOGGER.info("Reconciling ManagedBridge: '{}' in namespace '{}'",
                managedBridge.getMetadata().getName(),
                managedBridge.getMetadata().getNamespace());

        ManagedBridgeStatus status = managedBridge.getStatus();

        //
        //        if (!status.isReady() && isTimedOut(status)) {
        //            notifyManagerOfFailure(bridgeIngress,
        //                                   new ProvisioningTimeOutException(String.format(ProvisioningTimeOutException.TIMEOUT_FAILURE_MESSAGE,
        //                                                                                  bridgeIngress.getClass().getSimpleName(),
        //                                                                                  bridgeIngress.getSpec().getId())));
        //            status.markConditionFalse(ConditionTypeConstants.READY);
        //            return UpdateControl.updateStatus(bridgeIngress);
        //        }

        Secret secret = managedBridgeService.fetchBridgeSecret(managedBridge);

        if (secret == null) {
            LOGGER.info("The Secret for the ManagedBridge with id '{}' has been not created yet.",
                    managedBridge.getSpec().getId());
            if (!status.isConditionTypeFalse(ConditionTypeConstants.READY)) {
                status.markConditionFalse(ConditionTypeConstants.READY);
            }
            if (!status.isConditionTypeFalse(ManagedBridgeStatus.SECRET_AVAILABLE)) {
                status.markConditionFalse(ManagedBridgeStatus.SECRET_AVAILABLE);
            }
            return UpdateControl.updateStatus(managedBridge).rescheduleAfter(reconcileIntervalSeconds);
        } else if (!status.isConditionTypeTrue(ManagedBridgeStatus.SECRET_AVAILABLE)) {
            LOGGER.info("Secret for ManagedBridge with id '{}' has been created.", managedBridge.getSpec().getId());
            status.markConditionTrue(ManagedBridgeStatus.SECRET_AVAILABLE);
        }

        // Nothing to check for ConfigMap
        ConfigMap configMap = managedBridgeService.fetchOrCreateBridgeConfigMap(managedBridge, secret);
        if (!status.isConditionTypeTrue(ManagedBridgeStatus.CONFIG_MAP_AVAILABLE)) {
            status.markConditionTrue(ManagedBridgeStatus.CONFIG_MAP_AVAILABLE);
        }

        KnativeBroker knativeBroker = managedBridgeService.fetchOrCreateKnativeBroker(managedBridge, configMap);
        String path = extractBrokerPath(knativeBroker);

        if (path == null) {
            LOGGER.info("The Knative Broker Resource for ManagedBridge '{}' in namespace '{}' is not ready",
                    managedBridge.getMetadata().getName(),
                    managedBridge.getMetadata().getNamespace());
            if (!status.isConditionTypeFalse(ConditionTypeConstants.READY)) {
                status.markConditionFalse(ConditionTypeConstants.READY);
            }
            if (!status.isConditionTypeFalse(ManagedBridgeStatus.KNATIVE_BROKER_AVAILABLE)) {
                status.markConditionFalse(ManagedBridgeStatus.KNATIVE_BROKER_AVAILABLE);
            }
            return UpdateControl.updateStatus(managedBridge).rescheduleAfter(reconcileIntervalSeconds);
        } else if (!status.isConditionTypeTrue(ManagedBridgeStatus.KNATIVE_BROKER_AVAILABLE)) {
            status.markConditionTrue(ManagedBridgeStatus.KNATIVE_BROKER_AVAILABLE);
            LOGGER.info("The Knative Broker Resource for ManagedBridge '{}' in namespace '{}' is ready", managedBridge.getMetadata().getName(), managedBridge.getMetadata().getNamespace());
        }

        // TODO - Provisioning of Ingress will be added by https://issues.redhat.com/browse/MGDOBR-1244

        if (!status.isReady()) {
            metricsService.onOperationComplete(managedBridge, MetricsOperation.CONTROLLER_RESOURCE_PROVISION);
            status.markConditionTrue(ConditionTypeConstants.READY);
            //TODO - Callback to Manager will be added in https://issues.redhat.com/browse/MGDOBR-1267
            //notifyManager(bridgeIngress, ManagedResourceStatus.READY);
            return UpdateControl.updateStatus(managedBridge);
        }

        return UpdateControl.noUpdate();
    }

    @Override
    public DeleteControl cleanup(ManagedBridge resource, Context context) {
        return null;
    }

    @Override
    public List<EventSource> prepareEventSources(EventSourceContext<ManagedBridge> context) {

        List<EventSource> eventSources = new ArrayList<>();
        eventSources.add(EventSourceFactory.buildSecretsInformer(kubernetesClient, LabelsBuilder.V2_OPERATOR_NAME, ManagedBridge.COMPONENT_NAME));
        eventSources.add(EventSourceFactory.buildConfigMapsInformer(kubernetesClient, LabelsBuilder.V2_OPERATOR_NAME, ManagedBridge.COMPONENT_NAME));
        eventSources.add(EventSourceFactory.buildBrokerInformer(kubernetesClient, LabelsBuilder.V2_OPERATOR_NAME, ManagedBridge.COMPONENT_NAME));
        eventSources.add(EventSourceFactory.buildAuthorizationPolicyInformer(kubernetesClient, LabelsBuilder.V2_OPERATOR_NAME, ManagedBridge.COMPONENT_NAME));
        eventSources.add(networkingService.buildInformerEventSource(LabelsBuilder.V2_OPERATOR_NAME, ManagedBridge.COMPONENT_NAME));

        return eventSources;
    }

    @Override
    public Optional<ManagedBridge> updateErrorStatus(ManagedBridge resource, RetryInfo retryInfo, RuntimeException e) {
        return Optional.empty();
    }

    private String extractBrokerPath(KnativeBroker broker) {
        if (broker == null || broker.getStatus() == null || broker.getStatus().getAddress().getUrl() == null) {
            return null;
        }
        try {
            return new URL(broker.getStatus().getAddress().getUrl()).getPath();
        } catch (MalformedURLException e) {
            LOGGER.info("Could not extract URL of the broker of ManagedBridge '{}'", broker.getMetadata().getName());
            return null;
        }
    }
}
