/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.storage.opensearch2.views.searchtypes;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.graylog.plugins.views.search.Query;
import org.graylog.plugins.views.search.SearchJob;
import org.graylog.plugins.views.search.SearchType;
import org.graylog.plugins.views.search.searchtypes.events.CommonEventSummary;
import org.graylog.plugins.views.search.searchtypes.events.EventList;
import org.graylog.plugins.views.search.searchtypes.events.EventSummary;
import org.graylog.shaded.opensearch2.org.opensearch.action.search.SearchResponse;
import org.graylog.shaded.opensearch2.org.opensearch.index.query.BoolQueryBuilder;
import org.graylog.shaded.opensearch2.org.opensearch.index.query.QueryBuilders;
import org.graylog.shaded.opensearch2.org.opensearch.search.SearchHit;
import org.graylog.shaded.opensearch2.org.opensearch.search.aggregations.Aggregations;
import org.graylog.shaded.opensearch2.org.opensearch.search.sort.SortOrder;
import org.graylog.storage.opensearch2.views.OSGeneratedQueryContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class OSEventList implements EventListStrategy {
    @Override
    public void doGenerateQueryPart(Query query, EventList eventList,
                                    OSGeneratedQueryContext queryContext) {
        final var searchSourceBuilder = queryContext.searchSourceBuilder(eventList);
        final var sortConfig = eventList.sortWithDefault();
        searchSourceBuilder.sort(sortConfig.field(), toSortOrder(sortConfig.direction()));
        final var queryBuilder = searchSourceBuilder.query();
        if (!eventList.attributes().isEmpty() && queryBuilder instanceof BoolQueryBuilder boolQueryBuilder) {
            final var filterQueries = eventList.attributes().stream()
                    .flatMap(attribute -> attribute.toQueryStrings().stream())
                    .toList();

            filterQueries.forEach(filterQuery -> boolQueryBuilder.filter(QueryBuilders.queryStringQuery(filterQuery)));
        }

        eventList.page().ifPresentOrElse(page -> {
            final var pageSize = eventList.perPage().orElse(EventList.DEFAULT_PAGE_SIZE);
            searchSourceBuilder.size(pageSize);
            searchSourceBuilder.from((page - 1) * pageSize);
        }, () -> searchSourceBuilder.size(10000));
    }

    private SortOrder toSortOrder(EventList.Direction direction) {
        return switch (direction) {
            case ASC -> SortOrder.ASC;
            case DESC -> SortOrder.DESC;
        };
    }

    protected List<Map<String, Object>> extractResult(SearchResponse result) {
        return StreamSupport.stream(result.getHits().spliterator(), false)
                .map(SearchHit::getSourceAsMap)
                .collect(Collectors.toList());
    }

    @WithSpan
    @Override
    public SearchType.Result doExtractResult(SearchJob job, Query query, EventList searchType, SearchResponse result,
                                             Aggregations aggregations, OSGeneratedQueryContext queryContext) {
        final Set<String> effectiveStreams = searchType.streams().isEmpty()
                ? query.usedStreamIds()
                : searchType.streams();
        final List<CommonEventSummary> eventSummaries = extractResult(result).stream()
                .map(EventSummary::parse)
                .filter(eventSummary -> effectiveStreams.containsAll(eventSummary.streams()))
                .collect(Collectors.toList());
        final EventList.Result.Builder resultBuilder = EventList.Result.builder()
                .events(eventSummaries)
                .id(searchType.id())
                .totalResults(result.getHits().getTotalHits().value);
        searchType.name().ifPresent(resultBuilder::name);
        return resultBuilder.build();
    }
}
