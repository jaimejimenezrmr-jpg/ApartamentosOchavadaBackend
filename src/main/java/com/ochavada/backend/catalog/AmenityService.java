package com.ochavada.backend.catalog;

import com.ochavada.backend.catalog.dto.AmenityRequest;
import com.ochavada.backend.catalog.dto.AmenityResponse;
import com.ochavada.backend.common.error.ConflictException;
import com.ochavada.backend.common.error.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AmenityService {

    private final AmenityRepository repository;

    public AmenityService(AmenityRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AmenityResponse> list() {
        return repository.findAllByOrderByCategoryAscNameAsc().stream().map(AmenityResponse::from).toList();
    }

    @Transactional
    public AmenityResponse create(AmenityRequest request) {
        if (repository.existsByCode(request.code())) {
            throw duplicateCode(request.code());
        }
        Amenity amenity = new Amenity(request.code(), request.name().trim(), blankToNull(request.icon()),
                request.category());
        return AmenityResponse.from(repository.saveAndFlush(amenity));
    }

    @Transactional
    public AmenityResponse update(UUID id, AmenityRequest request) {
        Amenity amenity = find(id);
        if (repository.existsByCodeAndIdNot(request.code(), id)) {
            throw duplicateCode(request.code());
        }
        amenity.setCode(request.code());
        amenity.setName(request.name().trim());
        amenity.setIcon(blankToNull(request.icon()));
        amenity.setCategory(request.category());
        return AmenityResponse.from(repository.saveAndFlush(amenity));
    }

    @Transactional
    public void delete(UUID id) {
        Amenity amenity = find(id);
        if (repository.isInUse(id)) {
            throw new ConflictException("amenity.in_use",
                    "El servicio está asignado a alguna tipología; quítelo de ellas antes de eliminarlo");
        }
        repository.delete(amenity);
        repository.flush();
    }

    private Amenity find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("servicio", id));
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("amenity.duplicate_code", "Ya existe un servicio con el código " + code);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
