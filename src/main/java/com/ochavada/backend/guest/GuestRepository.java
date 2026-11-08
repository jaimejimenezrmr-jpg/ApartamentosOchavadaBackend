package com.ochavada.backend.guest;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestRepository extends JpaRepository<Guest, UUID> {

    /**
     * Búsqueda libre por nombre completo, email, teléfono o número de documento. {@code term} debe llegar
     * en minúsculas y ya rodeado de comodines.
     */
    @Query(value = """
            select g from Guest g
             where lower(concat(g.firstName, ' ', g.lastName1, ' ', coalesce(g.lastName2, ''))) like :term
                or lower(coalesce(g.email, '')) like :term
                or lower(coalesce(g.phone, '')) like :term
                or lower(coalesce(g.documentNumber, '')) like :term
            """,
            countQuery = """
            select count(g) from Guest g
             where lower(concat(g.firstName, ' ', g.lastName1, ' ', coalesce(g.lastName2, ''))) like :term
                or lower(coalesce(g.email, '')) like :term
                or lower(coalesce(g.phone, '')) like :term
                or lower(coalesce(g.documentNumber, '')) like :term
            """)
    Page<Guest> search(@Param("term") String term, Pageable pageable);
}
