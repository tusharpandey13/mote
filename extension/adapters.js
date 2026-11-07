// Site adapters: data only (CSS selectors), read by content.js. Each field is optional; anything an
// adapter doesn't name falls back to the generic path (the largest visible <video>, its textTracks,
// and buttons whose text looks like "Skip ad" / "Skip intro"). Keep entries small: sites change markup.
//   hosts:    RegExp on location.hostname
//   title:    element whose text is the video title (else document.title)
//   next/prev: control to click
//   captions: { button, on } - `on` is a selector the button matches while captions are on
//   skips:    { ad|intro|recap|credits: [selectors] } - visible = available
//   pageSeek: seek through the page-world player API (netflix-main.js) instead of currentTime
//   live:     selector present while the stream is live
self.MOTE_ADAPTERS = [
  {
    site: 'youtube',
    hosts: /(^|\.)youtube\.com$/,
    title: 'h1.ytd-watch-metadata yt-formatted-string, #title h1, h1.title',
    next: '.ytp-next-button:not([aria-disabled="true"])',
    prev: '.ytp-prev-button:not([aria-disabled="true"])',
    captions: { button: '.ytp-subtitles-button', on: '[aria-pressed="true"]' },
    skips: { ad: ['.ytp-skip-ad-button', '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern'] },
    live: '.ytp-live-badge:not([disabled])',
  },
  {
    site: 'netflix',
    hosts: /(^|\.)netflix\.com$/,
    title: '[data-uia="video-title"]',
    next: '[data-uia="control-next"]',
    pageSeek: true,
    skips: {
      intro: ['[data-uia="player-skip-intro"]'],
      recap: ['[data-uia="player-skip-recap"]'],
      credits: ['[data-uia="next-episode-seamless-button"]', '[data-uia="next-episode-seamless-button-draining"]'],
    },
  },
  {
    site: 'prime',
    hosts: /(^|\.)(primevideo\.com|amazon\.(com|co\.uk|de|fr|it|es|ca|co\.jp|in|com\.au))$/,
    title: '.atvwebplayersdk-title-text',
    next: '.atvwebplayersdk-nexttitle-button',
    captions: { button: '.atvwebplayersdk-subtitleaudiomenu-button' },
    skips: {
      intro: ['.atvwebplayersdk-skipelement-button'],
      ad: ['.atvwebplayersdk-adskip-button', '.adSkipButton'],
      credits: ['.atvwebplayersdk-nextupcard-button'],
    },
  },
  {
    site: 'twitch',
    hosts: /(^|\.)twitch\.tv$/,
    title: '[data-a-target="stream-title"]',
    live: '.live-indicator-container, [data-a-target="player-state-live"]',
  },
];

// Generic skip buttons by visible text (also used on adapter sites as a fallback).
self.MOTE_SKIP_TEXT = { ad: /^skip( ads?)?$|skip ad/i, intro: /skip intro|skip opening/i, recap: /skip recap/i, credits: /^next episode$|skip credits/i };
