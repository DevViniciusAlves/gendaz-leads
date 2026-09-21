package com.gendaz.leads.controller;

import com.gendaz.leads.util.CountryCodeResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/meta")
public class MetadataController {

    @GetMapping("/countries")
    public List<CountryCodeResolver.CountryOption> getCountries() {
        return CountryCodeResolver.allCountriesPtBr();
    }
}