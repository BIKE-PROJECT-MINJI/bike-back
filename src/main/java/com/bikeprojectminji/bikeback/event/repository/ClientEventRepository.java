package com.bikeprojectminji.bikeback.event.repository;

import com.bikeprojectminji.bikeback.event.entity.ClientEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientEventRepository extends JpaRepository<ClientEventEntity, Long> {
}
