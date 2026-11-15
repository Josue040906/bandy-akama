package com.bandyakama.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public, lightweight address: the client application calls it when it opens
 * so that Bandy wakes up while the user signs in. It returns no data.
 */
@RestController
public class SanteController {

    @GetMapping("/api/sante")
    public Map<String, String> sante() {
        return Map.of("statut", "ok");
    }
}
