package com.ochavada.backend.lostitem;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LostItemRepository extends JpaRepository<LostItem, UUID> {

    @Query("""
            select l from LostItem l left join fetch l.room left join fetch l.reservation r left join fetch r.holder
             where (:openOnly = false or l.status in (com.ochavada.backend.lostitem.LostItemStatus.STORED,
                                                      com.ochavada.backend.lostitem.LostItemStatus.GUEST_NOTIFIED))
             order by l.foundAt desc
            """)
    List<LostItem> search(@Param("openOnly") boolean openOnly);

    List<LostItem> findByTaskIdOrderByFoundAtAsc(UUID taskId);

    long countByStatusIn(Collection<LostItemStatus> statuses);
}
