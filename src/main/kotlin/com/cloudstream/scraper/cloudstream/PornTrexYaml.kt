package com.cloudstream.scraper.cloudstream

const val EMBEDDED_PORNTREX_YAML = """
id: first-site
name: PornTrex
baseUrl: https://www.porntrex.com
version: 1
isNsfw: true
headers:
  User-Agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
  Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
  Accept-Language: "en-US,en;q=0.9"
  Cookie: "confirmed=true; age_verified=1; kt_tplayer=1; kt_lang=en"

search:
  urlTemplate: "/search/{query}/?page={page}"
  method: GET
  itemSelector: ".list-videos .item, .video-preview-screen, #list_videos_common_videos_list_items .item, .item"
  fields:
    id:
      selector: "a"
      attribute: "href"
    title:
      selector: "strong.title, p.inf a, .title, a[title]"
      extraction: text
      transforms:
        - trim: true
    url:
      selector: "a"
      attribute: "href"
    posterUrl:
      selector: "img.thumb, img.cover, img"
      attribute: "data-src"
      fallbacks:
        - selector: "img.thumb, img.cover, img"
          attribute: "src"
    type:
      default: "NSFW"
    rating:
      selector: ".rating"
      extraction: text
      transforms:
        - regex: "(\\d+)"
          group: 1
      type: double

catalogs:
  - id: latest
    name: Latest Videos
    type: MOVIES
    urlTemplate: "/latest-updates/?page={page}"
    itemSelector: ".list-videos .item, .video-preview-screen, .item"
    pagination:
      type: query
      param: page
      nextSelector: ".pagination .next, a.next"

  - id: actors
    name: Models & Stars
    type: PEOPLE
    urlTemplate: "/models/?page={page}"
    itemSelector: ".list-models .item, #list_models_models_list_items .item, a.item"
    fields:
      name:
        selector: "strong.title, .title"
        extraction: text
        transforms:
          - trim: true
      url:
        selector: "a"
        attribute: "href"
        fallbacks:
          - attribute: "href"
      photoUrl:
        selector: "img.thumb, img"
        attribute: "data-src"
        fallbacks:
          - selector: "img.thumb, img"
            attribute: "src"

  - id: top-rated
    name: Top Rated
    type: MOVIES
    urlTemplate: "/top-rated/?page={page}"
    itemSelector: ".list-videos .item, .video-preview-screen, .item"

  - id: most-popular
    name: Most Popular
    type: MOVIES
    urlTemplate: "/most-popular/?page={page}"
    itemSelector: ".list-videos .item, .video-preview-screen, .item"

details:
  common:
    id:
      selector: "meta[property='og:url']"
      attribute: "content"
    title:
      selector: "script:containsData(video_title), script:containsData(flashvars)"
      extraction: text
      transforms:
        - regex: "video_title:\\s*['\"]([^'\"]+)['\"]"
          group: 1
        - trim: true
      fallbacks:
        - selector: "h1.title, h1, .headline h1"
          extraction: text
          transforms:
            - regex: "^(.*?)(?:\\s*\\|.*)?$"
              group: 1
            - trim: true
    posterUrl:
      selector: "meta[property='og:image'], #player-holder video, .player-holder video"
      attribute: "poster"
      fallbacks:
        - selector: "meta[property='og:image']"
          attribute: "content"
    description:
      selector: ".videodesc .items-holder em.des-link, .videodesc .des-link, .videodesc .items-holder, .videodesc, .main-container .description-block, .description-block, .video-details, .description"
      extraction: text
      transforms:
        - replaceRegex: "^Description:\\s*"
          replacement: ""
        - trim: true
    rating:
      selector: ".vote-percentage, .rating"
      extraction: text
      transforms:
        - regex: "(\\d+)"
          group: 1
      type: double
    genres:
      selector: ".block-details .items-holder.js-categories a:not(.js-open-suggest), .items-holder.js-categories a:not(.js-open-suggest), .item-categories a, .tags a, .categories-wrapper a"
      extraction: text_list
    tags:
      selector: ".block-details .items-holder.js-categories a:not(.js-open-suggest), .items-holder.js-categories a:not(.js-open-suggest), .tags a, .item-tags a"
      extraction: text_list
    releaseYear:
      selector: ".info-block .item span:has(i.fa-calendar) em.badge, .info-block i.fa-calendar + em, .meta-year, .year"
      extraction: text
      transforms:
        - relativeYear: true
      type: int

  movie:
    duration:
      selector: ".info-block .item span:has(i.fa-clock-o) em.badge, .info-block i.fa-clock-o + em, .durations, .duration, .time"
      extraction: text
      transforms:
        - regex: "(\\d+)"
          group: 1
      type: int

people:
  itemSelector: ".block-details .items-holder a[href*='/models/'], .item-models a, .models-list a, .list-models .item"
  fields:
    name:
      selector: "self, a, strong.title, .title"
      extraction: text
      transforms:
        - trim: true
    url:
      selector: "self, a"
      attribute: "href"
    photoUrl:
      selector: "img.thumb, img"
      attribute: "data-src"
      fallbacks:
        - selector: "img.thumb, img"
          attribute: "src"
  detail:
    name:
      selector: ".profile-model-info .name h1, h1.title, h1"
      extraction: text
    biography:
      selector: ".main-container .description-block, .profile-model-info .description-block, .profile-model-info .description, .model-description, .description-block"
      extraction: text
      transforms:
        - trim: true
    photoUrl:
      selector: ".profile-model-info .img-holder img, .profile-model-info img, .img-holder img"
      attribute: "data-src"
      fallbacks:
        - selector: ".profile-model-info .img-holder img, .profile-model-info img, .img-holder img"
          attribute: "src"
        - selector: "meta[property='og:image']"
          attribute: "content"
    knownForSelector: ".video-preview-screen, .video-item, .list-videos .item"
    knownForFields:
      title:
        selector: "p.inf a, strong.title, .title, a[title]"
        extraction: text
      url:
        selector: "a.thumb, a"
        attribute: "href"
      posterUrl:
        selector: "img.cover, img.thumb, img"
        attribute: "data-src"
        fallbacks:
          - selector: "img.cover, img.thumb, img"
            attribute: "src"
"""
