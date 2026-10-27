package com.company.meetingroom.room;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    private String location;

    @Column(nullable = false)
    private int capacity;

    @ElementCollection
    @CollectionTable(name = "room_equipment", joinColumns = @JoinColumn(name = "room_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "equipment", nullable = false, length = 30)
    private Set<Equipment> equipment = new HashSet<>();

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Room() {
        // for JPA
    }

    public Room(String name, String location, int capacity, Set<Equipment> equipment) {
        update(name, location, capacity, equipment);
    }

    public void update(String name, String location, int capacity, Set<Equipment> equipment) {
        this.name = name;
        this.location = location;
        this.capacity = capacity;
        this.equipment.clear();
        if (equipment != null) {
            this.equipment.addAll(equipment);
        }
    }

    public boolean hasAllEquipment(Set<Equipment> required) {
        return required == null || required.isEmpty() || equipment.containsAll(required);
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public int getCapacity() {
        return capacity;
    }

    public Set<Equipment> getEquipment() {
        return equipment.isEmpty() ? EnumSet.noneOf(Equipment.class) : EnumSet.copyOf(equipment);
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
