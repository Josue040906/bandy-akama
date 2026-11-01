package com.bandyakama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchCriteriaValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsMustAndRequiredShouldCriteria() {
        assertTrue(SearchCriteriaValidator.hasPositiveCriteria("""
                {"must":[{"field":"service","operator":"CONTAINS","value":"Personnel"}]}
                """, mapper));
        assertTrue(SearchCriteriaValidator.hasPositiveCriteria("""
                {"must":[],"should":[
                  {"field":"poste","operator":"CONTAINS","value":"Gestionnaire RH"},
                  {"field":"poste","operator":"CONTAINS","value":"Gestionnaire de dossiers"}
                ],"minimum_should_match":1}
                """, mapper));
    }

    @Test
    void rejectsEmptyNegativeOnlyAndOptionalOnlyCriteria() {
        assertFalse(SearchCriteriaValidator.hasPositiveCriteria("""
                {"must":[],"should":[],"must_not":[]}
                """, mapper));
        assertFalse(SearchCriteriaValidator.hasPositiveCriteria("""
                {"must_not":[{"field":"poste","operator":"CONTAINS","value":"Gestionnaire RH"}]}
                """, mapper));
        assertFalse(SearchCriteriaValidator.hasPositiveCriteria("""
                {"should":[{"field":"poste","operator":"CONTAINS","value":"Gestionnaire RH"}],
                 "minimum_should_match":0}
                """, mapper));
    }

    @Test
    void acceptsLegacyFiltersAndNestedBooleanGroups() {
        assertTrue(SearchCriteriaValidator.hasPositiveCriteria("""
                {"filters":[{"field":"nom","operator":"FUZZY","value":"RASOLOARISOA"}]}
                """, mapper));
        assertTrue(SearchCriteriaValidator.hasPositiveCriteria("""
                {"must":[{"bool":{"should":[
                  {"field":"poste","operator":"CONTAINS","value":"Gestionnaire RH"}
                ],"minimum_should_match":1}}]}
                """, mapper));
    }

    @Test
    void rejectsMissingMalformedAndNonObjectCriteria() {
        assertFalse(SearchCriteriaValidator.hasPositiveCriteria(null, mapper));
        assertFalse(SearchCriteriaValidator.hasPositiveCriteria("", mapper));
        assertFalse(SearchCriteriaValidator.hasPositiveCriteria("{", mapper));
        assertFalse(SearchCriteriaValidator.hasPositiveCriteria("[]", mapper));
    }
}
