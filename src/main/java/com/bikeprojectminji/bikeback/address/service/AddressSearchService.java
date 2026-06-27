package com.bikeprojectminji.bikeback.address.service;

import com.bikeprojectminji.bikeback.address.dto.AddressCandidateResponse;
import com.bikeprojectminji.bikeback.address.dto.AddressSearchResponse;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.global.metrics.MeasuredOperation;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AddressSearchService {

    private static final Logger log = LoggerFactory.getLogger(AddressSearchService.class);

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 5;
    private static final int MAX_SIZE = 10;
    private static final int MAX_QUERY_LENGTH = 120;

    private final List<AddressSearchClient> addressSearchClients;
    private final BikeMetricsRecorder bikeMetricsRecorder;

    public AddressSearchService(List<AddressSearchClient> addressSearchClients) {
        this(addressSearchClients, new BikeMetricsRecorder(Metrics.globalRegistry));
    }

    @Autowired
    public AddressSearchService(List<AddressSearchClient> addressSearchClients, BikeMetricsRecorder bikeMetricsRecorder) {
        this.addressSearchClients = List.copyOf(addressSearchClients);
        this.bikeMetricsRecorder = bikeMetricsRecorder;
    }

    @MeasuredOperation("address.search")
    public AddressSearchResponse search(String rawQuery, Integer page, Integer size) {
        AddressSearchQuery query = normalizeQuery(rawQuery, page, size);
        AddressSearchOutcome outcome = searchWithFallback(query);
        return toResponse(query, outcome);
    }

    private AddressSearchOutcome searchWithFallback(AddressSearchQuery query) {
        AddressSearchProviderResult firstResult = null;
        AddressSearchProviderResult lastResult = null;
        for (AddressSearchClient client : addressSearchClients) {
            long startedAtNanos = System.nanoTime();
            AddressSearchProviderResult result = client.search(query);
            if (firstResult == null) {
                firstResult = result;
            }
            bikeMetricsRecorder.recordProviderCall(
                    result.provider(),
                    "address_search",
                    result.status().name(),
                    Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
            if (result.status() == AddressSearchProviderStatus.SUCCESS) {
                AddressSearchOutcome outcome = AddressSearchOutcome.from(firstResult, result);
                logFallbackIfNeeded(outcome);
                return outcome;
            }
            lastResult = result;
        }
        if (lastResult != null) {
            AddressSearchOutcome outcome = AddressSearchOutcome.from(firstResult, lastResult);
            logFallbackIfNeeded(outcome);
            return outcome;
        }
        return AddressSearchOutcome.from(null, AddressSearchProviderResult.providerFailure("NONE"));
    }

    private AddressSearchQuery normalizeQuery(String rawQuery, Integer page, Integer size) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new BadRequestException("query는 비어 있을 수 없습니다.");
        }
        String normalizedQuery = rawQuery.trim();
        if (normalizedQuery.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("query는 " + MAX_QUERY_LENGTH + "자 이하여야 합니다.");
        }
        int normalizedPage = page == null ? DEFAULT_PAGE : page;
        int normalizedSize = size == null ? DEFAULT_SIZE : size;
        if (normalizedPage < 1) {
            throw new BadRequestException("page는 1 이상이어야 합니다.");
        }
        if (normalizedSize < 1 || normalizedSize > MAX_SIZE) {
            throw new BadRequestException("size는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
        return new AddressSearchQuery(normalizedQuery, normalizedPage, normalizedSize);
    }

    private AddressSearchResponse toResponse(AddressSearchQuery query, AddressSearchOutcome outcome) {
        AddressSearchProviderResult providerResult = outcome.result();
        List<AddressCandidateResponse> candidates = providerResult.candidates().stream()
                .limit(query.size())
                .map(AddressCandidate::toResponse)
                .toList();
        String status = determineStatus(providerResult, candidates);
        return new AddressSearchResponse(
                status,
                query.page(),
                query.size(),
                candidates.size(),
                providerResult.provider(),
                outcome.primaryProvider(),
                outcome.fallbackUsed(),
                outcome.fallbackReason(),
                messageFor(status),
                candidates
        );
    }

    private String determineStatus(AddressSearchProviderResult providerResult, List<AddressCandidateResponse> candidates) {
        if (providerResult.status() == AddressSearchProviderStatus.RATE_LIMITED) {
            return "RATE_LIMITED";
        }
        if (providerResult.status() == AddressSearchProviderStatus.PROVIDER_FAILURE) {
            return "PROVIDER_FAILURE";
        }
        if (providerResult.status() == AddressSearchProviderStatus.EMPTY || candidates.isEmpty()) {
            return "EMPTY";
        }
        if (candidates.size() > 1) {
            return "AMBIGUOUS";
        }
        return "SUCCESS";
    }

    private String messageFor(String status) {
        return switch (status) {
            case "SUCCESS" -> "주소 후보를 찾았습니다.";
            case "AMBIGUOUS" -> "주소 후보가 여러 개입니다.";
            case "EMPTY" -> "검색 결과가 없습니다.";
            case "RATE_LIMITED" -> "주소 검색 요청이 일시적으로 많아 잠시 후 다시 시도하세요.";
            case "PROVIDER_FAILURE" -> "주소 검색 provider를 사용할 수 없습니다.";
            default -> "주소 검색 상태를 확인하세요.";
        };
    }

    private void logFallbackIfNeeded(AddressSearchOutcome outcome) {
        if (!outcome.fallbackUsed()) {
            return;
        }
        log.warn(
                "address_search_provider_fallback primary_provider={} fallback_provider={} fallback_reason={}",
                outcome.primaryProvider(),
                outcome.result().provider(),
                outcome.fallbackReason()
        );
    }

    private record AddressSearchOutcome(
            AddressSearchProviderResult result,
            String primaryProvider,
            boolean fallbackUsed,
            String fallbackReason
    ) {

        private static AddressSearchOutcome from(
                AddressSearchProviderResult firstResult,
                AddressSearchProviderResult result
        ) {
            String primaryProvider = firstResult == null ? result.provider() : firstResult.provider();
            boolean fallbackUsed = firstResult != null
                    && !firstResult.provider().equals(result.provider());
            String fallbackReason = fallbackUsed
                    ? firstResult.provider() + "_" + firstResult.status().name()
                    : null;
            return new AddressSearchOutcome(result, primaryProvider, fallbackUsed, fallbackReason);
        }
    }
}
