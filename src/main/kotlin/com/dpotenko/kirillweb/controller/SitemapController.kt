package com.dpotenko.kirillweb.controller

import com.dpotenko.kirillweb.service.sitemap.SitemapService
import com.dpotenko.kirillweb.service.sitemap.UrlsSet
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/sitemap")
class SitemapController(private val sitemapService: SitemapService) {

    @GetMapping(produces = [MediaType.APPLICATION_XML_VALUE])
    fun generateSitemap(): UrlsSet {
        return sitemapService.generate();
    }
}
