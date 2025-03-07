package com.redhat.service.smartevents.manager.v1.workers.resources;

import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.service.smartevents.infra.core.models.ManagedResourceStatus;
import com.redhat.service.smartevents.manager.v1.persistence.dao.ConnectorsDAO;
import com.redhat.service.smartevents.manager.v1.persistence.dao.ProcessorDAO;
import com.redhat.service.smartevents.manager.v1.persistence.models.ConnectorEntity;
import com.redhat.service.smartevents.manager.v1.persistence.models.Processor;
import com.redhat.service.smartevents.manager.v1.workers.Work;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

@ApplicationScoped
public class ProcessorWorker extends AbstractWorker<Processor> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessorWorker.class);

    @Inject
    ProcessorDAO processorDAO;

    @Inject
    ConnectorsDAO connectorsDAO;

    @Inject
    ConnectorWorker connectorWorker;

    @Override
    public PanacheRepositoryBase<Processor, String> getDao() {
        return processorDAO;
    }

    @Override
    public String getId(Work work) {
        // The ID of the ManagedResource to process is stored directly in the JobDetail.
        return work.getManagedResourceId();
    }

    @Override
    public Processor createDependencies(Work work, Processor processor) {
        LOGGER.info("Creating dependencies for '{}' [{}]",
                processor.getName(),
                processor.getId());
        // Transition resource to PREPARING status.
        // PROVISIONING is handled by the Operator.
        processor.setStatus(ManagedResourceStatus.PREPARING);
        processor = persist(processor);

        if (hasZeroConnectors(processor)) {
            LOGGER.debug(
                    "No dependencies required for '{}' [{}]",
                    processor.getName(),
                    processor.getId());
            processor.setDependencyStatus(ManagedResourceStatus.READY);
            return persist(processor);
        }

        // If we have to deploy a Managed Connector, delegate to the ConnectorWorker.
        // The Processor will be provisioned by the Shard when it is in ACCEPTED state *and* Connectors are READY (or null).
        ConnectorEntity updatedConnectorEntity = connectorWorker.handleWork(work);
        processor.setDependencyStatus(updatedConnectorEntity.getStatus());

        // If the Connector failed we should mark the Processor as failed too
        if (updatedConnectorEntity.getStatus() == ManagedResourceStatus.FAILED) {
            processor.setStatus(ManagedResourceStatus.FAILED);
            processor.setErrorId(updatedConnectorEntity.getErrorId());
            processor.setErrorUUID(updatedConnectorEntity.getErrorUUID());
        }

        return persist(processor);
    }

    @Override
    public boolean isProvisioningComplete(Processor managedResource) {
        //As far as the Worker mechanism is concerned work for a Processor is complete when the dependencies are complete.
        return PROVISIONING_COMPLETED.contains(managedResource.getDependencyStatus());
    }

    @Override
    public Processor deleteDependencies(Work work, Processor processor) {
        LOGGER.info("Destroying dependencies for '{}' [{}]",
                processor.getName(),
                processor.getId());

        if (hasZeroConnectors(processor)) {
            LOGGER.debug("No dependencies required for '{}' [{}]",
                    processor.getName(),
                    processor.getId());
            processor.setDependencyStatus(ManagedResourceStatus.DELETED);
            return persist(processor);
        }

        // If we have to delete a Managed Connector, delegate to the ConnectorWorker.
        ConnectorEntity updatedConnectorEntity = connectorWorker.handleWork(work);
        processor.setDependencyStatus(updatedConnectorEntity.getStatus());

        // If the Connector failed we should mark the Processor as failed too
        if (updatedConnectorEntity.getStatus() == ManagedResourceStatus.FAILED) {
            processor.setStatus(ManagedResourceStatus.FAILED);
            processor.setErrorId(updatedConnectorEntity.getErrorId());
            processor.setErrorUUID(updatedConnectorEntity.getErrorUUID());
        }

        return persist(processor);
    }

    @Override
    public boolean isDeprovisioningComplete(Processor managedResource) {
        //As far as the Worker mechanism is concerned work for a Processor is complete when the dependencies are complete.
        return DEPROVISIONING_COMPLETED.contains(managedResource.getDependencyStatus());
    }

    protected boolean hasZeroConnectors(Processor processor) {
        return Objects.isNull(getConnectorEntity(processor));
    }

    protected ConnectorEntity getConnectorEntity(Processor processor) {
        return connectorsDAO.findByProcessorId(processor.getId());
    }
}
