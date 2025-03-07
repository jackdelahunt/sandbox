package com.redhat.service.smartevents.manager.v1.models;

import java.time.ZonedDateTime;

import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.MappedSuperclass;

import com.redhat.service.smartevents.infra.core.exceptions.HasErrorInformation;
import com.redhat.service.smartevents.infra.core.models.ManagedResourceStatus;
import com.redhat.service.smartevents.manager.core.models.ManagedResource;

@MappedSuperclass
public class ManagedResourceV1 extends ManagedResource implements HasErrorInformation {

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    protected ManagedResourceStatus status;

    @Column(name = "dependency_status")
    @Enumerated(EnumType.STRING)
    protected ManagedResourceStatus dependencyStatus;

    @Column(name = "modified_at", columnDefinition = "TIMESTAMP")
    private ZonedDateTime modifiedAt;

    @Column(name = "deletion_requested_at", columnDefinition = "TIMESTAMP")
    private ZonedDateTime deletionRequestedAt;

    @Column(name = "error_id")
    private Integer errorId;

    @Column(name = "error_uuid")
    private String errorUUID;

    public ManagedResourceStatus getStatus() {
        return status;
    }

    public void setStatus(ManagedResourceStatus status) {
        this.status = status;
    }

    public ManagedResourceStatus getDependencyStatus() {
        return dependencyStatus;
    }

    public void setDependencyStatus(ManagedResourceStatus dependencyStatus) {
        this.dependencyStatus = dependencyStatus;
    }

    public ZonedDateTime getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(ZonedDateTime modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public ZonedDateTime getDeletionRequestedAt() {
        return deletionRequestedAt;
    }

    public void setDeletionRequestedAt(ZonedDateTime deletionRequestedAt) {
        this.deletionRequestedAt = deletionRequestedAt;
    }

    @Override
    public Integer getErrorId() {
        return errorId;
    }

    public void setErrorId(Integer errorId) {
        this.errorId = errorId;
    }

    @Override
    public String getErrorUUID() {
        return errorUUID;
    }

    public void setErrorUUID(String errorUUID) {
        this.errorUUID = errorUUID;
    }

    public boolean isActionable() {
        // A ManagedResource can only be modified or deleted if it's in READY or FAILED state
        return getStatus() == ManagedResourceStatus.READY || getStatus() == ManagedResourceStatus.FAILED;
    }

}
