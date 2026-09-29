package com.pahappa.musicplayer.repo;

import com.pahappa.musicplayer.domain.Track;
import com.pahappa.musicplayer.domain.TrackStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackRepository extends JpaRepository<Track, UUID> {

    Optional<Track> findByYoutubeVideoId(String youtubeVideoId);

    boolean existsByYoutubeVideoId(String youtubeVideoId);

    Page<Track> findByStatusOrderByCreatedAtDesc(TrackStatus status, Pageable pageable);

    /**
     * Case-insensitive substring search across the fields a user would actually
     * type. Escaped with a leading wildcard via {@code %:term%} so a term
     * containing {@code %} or {@code _} cannot break the LIKE clause.
     */
    @Query("""
            select t from Track t
            where lower(t.title) like :term
               or lower(coalesce(t.artist, '')) like :term
               or lower(coalesce(t.album, '')) like :term
            order by t.createdAt desc
            """)
    Page<Track> search(@Param("term") String term, Pageable pageable);

    long countByStatus(TrackStatus status);

    /** Startup reconciliation: anything left mid-flight belongs to a dead process. */
    List<Track> findByStatusIn(List<TrackStatus> statuses);

    /** Loudness backfill: finished tracks that have not been measured yet. */
    List<Track> findByStatusAndLoudnessLufsIsNull(TrackStatus status);
}
