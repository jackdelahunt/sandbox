package com.redhat.service.smartevents.manager.v2.persistence.models;

import java.util.List;
import java.util.Objects;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.redhat.service.smartevents.infra.v2.api.models.processors.ProcessorDefinition;

@NamedQueries({
        @NamedQuery(name = "PROCESSOR_V2.findByBridgeIdAndName",
                query = "from Processor_V2 p where p.name=:name and p.bridge.id=:bridgeId"),
        @NamedQuery(name = "PROCESSOR_V2.countByBridgeIdAndCustomerId",
                query = "select count(p.id) from Processor_V2 p where p.bridge.id=:bridgeId and p.bridge.customerId=:customerId")
})
@Entity(name = "Processor_V2")
@Table(name = "PROCESSOR_V2", uniqueConstraints = { @UniqueConstraint(columnNames = { "name", "bridge_id" }) })
public class Processor extends ManagedDefinedResourceV2<ProcessorDefinition> {

    public static final String BRIDGE_ID_PARAM = "bridgeId";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bridge_id")
    private Bridge bridge;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "processor_id")
    private List<Condition> conditions;

    public Processor() {
    }

    public Processor(String name) {
        this.name = name;
    }

    public Bridge getBridge() {
        return bridge;
    }

    public void setBridge(Bridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        if (Objects.isNull(this.conditions)) {
            this.conditions = conditions;
        } else {
            // Hibernate manages the underlying collection to handle one-to-many orphan removal.
            // If we replace the underlying collection Hibernate complains that its managed collection
            // becomes disconnected. Therefore, clear it and add all.
            this.conditions.clear();
            this.conditions.addAll(conditions);
        }
    }

    /*
     * See: https://vladmihalcea.com/how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier/
     * In the context of JPA equality, our id is our unique business key as we generate it via UUID.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Processor processor = (Processor) o;
        return id.equals(processor.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Processor{" +
                "definition=" + definition +
                ", id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", submittedAt=" + submittedAt +
                ", publishedAt=" + publishedAt +
                ", bridge=" + bridge +
                '}';
    }
}
