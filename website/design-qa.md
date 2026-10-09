# 拾件簿官网设计验收

Source visual truth: selected option 2, retained as `qa/selected-reference.png` (original generated reference `exec-adde6e56-7f32-4513-831e-22f07b567eeb.png`).

Implementation: `http://127.0.0.1:5173/`; screenshots `qa/desktop-v1.jpg`, `qa/desktop-final.jpg`.
Viewport: 1476 × 1066 CSS px, devicePixelRatio approximately 1. Source: 1476 × 1066 px. Extension screenshot: 1461 × 1055 px; equal-aspect downsampling approximately 0.99. Browser scrollbar occupies about 15 CSS px. Full-view comparisons normalize both images to 700 px width; focused comparisons normalize to 1476 px width. Same light/default/initial hero state. Initial images opened together in one comparison tool input; final comparison puts both together on a browser-rendered board.

## Iteration 1 — blocked

- [P1] Version line overlaps the generated phone bezel. Reference leaves about 28 px after metadata; generated asset moved phone top from y530 to y502. Fix: correct the raster asset phone placement and preserve whitespace.
- [P2] Display headline is visibly narrower and heavier than source. Fix: use self-hosted Noto Sans SC at weight 800, 70 px and normal tracking, reduce headline top gap and CTA top gap to align reference rhythm.
- [P2] Responsive art must retain natural aspect ratio and reserve space after copy at intermediate widths; the first CSS could put phone behind metadata. Fix: bottom-align natural-ratio raster, provide 980 px desktop minimum and 950 px tablet minimum, with a separate mobile scene row.
- Mobile GitHub icon needs explicit accessible name when its visible label is hidden. Fixed with aria-label.

Required surfaces: typography and spacing need above corrections. Colors match navy/blue/warm-white direction. Image asset uses image_gen with actual app/widget references and supplied logo. Supporting copy corrected to the app's real local image recognition behavior. Icon library used for UI actions.

## Iteration 2 — passed

Full-view and focused comparison: `qa/comparison-final.jpg`. The board contains both reference and rendered implementation together: equal-width full views, 1:1 typography/CTA/version crops, and product-scene crops. Source screenshot is not recreated with CSS. Corrected raster now starts the phone at y530, with bottom around y960. The metadata line is clear of the phone. Display font, tracking, headline position and CTA gaps were corrected and recaptured. The temporary browser viewport override needed resetting after a reload; the invalid capture was discarded, and dimensions/DOM viewport were checked before acceptance.

### Required fidelity surfaces

- **Fonts and typography:** self-hosted Noto Sans SC variable subset (wght 100–900 verified); headline 70 px / weight 800 / normal tracking on reference desktop, two intended lines preserved. Body hierarchy and optical weight follow reference. Mobile uses three short balanced lines instead of awkward sentence wrapping. The same font loads without external font requests.
- **Spacing and layout rhythm:** centered hero, original still-life composition, restrained feature strip and rounded download action retained. Metadata and phone no longer overlap. Natural-ratio raster and separate mobile scene row maintain breathing room at 1476, 768, 390 and 320 CSS px widths. Tested all links remain within the 320 px viewport; no horizontal page overflow.
- **Colors and tokens:** navy `#08263c`, blue download/action accents and warm-white background match the target direction. Supporting text uses darker slate for readable contrast. Pale-blue underline preserved; CSS line treatment differs slightly from the generated brush edge (P3 only).
- **Image quality and asset fidelity:** existing project SVG logo reused, Lucide/Simple Icons used for UI icons, image_gen scene uses supplied real app/widget references. No CSS drawings, handcrafted SVG art or fake placeholder product assets. Product screen text is part of the photographic reference; the lower actual screenshot is provided separately at readable size. Slight generated raster softness is acceptable at the intended hero scale.
- **Copy and app content:** incorrect mock claim about not performing image recognition corrected to local recognition/no upload. Current stable version is 0.5.20, Android 8.0+. Multi-code review/batch add and widget descriptions match source. Existing project logo differs from the generated round logo intentionally.

### Responsive and interaction evidence

- `qa/mobile-final.jpg`: 390 × 844 CSS px; `qa/mobile-full.jpg`: complete page including an expanded FAQ. `qa/tablet-final.jpg`: 768 × 1024 CSS px. Native screenshot density varies with the extension; DOM dimensions checked at each target.
- 320 × 740 CSS px: scrollWidth 320 and no clipped visible links. Main download target remains at least 275 × 58 px; top navigation remains usable and GitHub has an explicit accessible name.
- Usage navigation reaches `#how-it-works` with heading region at approximately y20 px after scroll settles. Start link reaches `#download`. FAQ expands with pointer and Enter key; answer copy is visible. Download labels and both hrefs point to the exact stable APK. Focus outlines, image alt text, native disclosure controls and reduced-motion CSS provided.
- Console checked at desktop and responsive states: no errors or warnings.
- Download HTTP verification: `qa/download-verification.json`; status 200, Android APK MIME type, attachment filename, 19,318,249 bytes, SHA-256 `b33c916dd5770238fd9cac11396044f70c64e31e4f8a5ca7618361689f7b2a28`. Entire file streamed and verified in about 3.5 seconds. Browser click was exercised, but the extension download-event waiter timed out, so browser completion is not asserted. The plain anchor and server download are verified.

### Open questions and follow-up polish

No unresolved P0/P1/P2 visual or functional findings. P3: generated underline texture and small raster letterforms can be refined if a future brand asset replaces the scene. User phone network and Android installation are outside this website verification.

## Iteration 3 — contrast refinement, passed

Numeric contrast checking found [P2] smaller semantic text colors at ratios 3.94–4.40: overline, section labels, step numbers, version line, screenshot caption and footer license. Darkened these blue/slate tokens without changing layout. Ratios after correction against warm-white: primary blue 5.00, muted slate 5.08, section blue 4.78, step blue 5.16. Recaptured desktop/mobile and repeated the combined full/focused comparison in `qa/comparison-final.jpg`; composition, type hierarchy and spacing preserved. No remaining actionable P0/P1/P2 findings.

## Implementation checklist

- [x] Recapture after asset/layout fixes and compare full and focused regions.
- [x] Check mobile, tablet, FAQ, anchors, download, font/resource loading and console.
- [x] Vite production build uses relative paths for GitHub project Pages.
- Live deployment verification is recorded separately in `../deliverables/website-publication.json`.

## Iteration 4 — wide-screen overlap regression, passed

User evidence exposed a P2 regression outside the original viewport matrix. At 2560 CSS px, the absolutely positioned scene grew while the shell was capped at 1250 px; phone top crossed metadata bottom by 226.32 px. Removed the fixed/capped shell height. Content now determines height, with bottom space derived from the scene's phone position plus a 32 px gap. Scene width is capped at the 1476 px reference composition; tablet uses its existing 1177 px composition and mobile retains its separate scene row.

Browser measurements at 3440, 2560, 1920 and 1476 px show a 32 px metadata-to-phone gap. At 1024/768 it is 57.97 px, and at 390/320 it is 83.01 px. All eight widths have no horizontal page overflow. Evidence: `../deliverables/website-overlap-checks.json`. Recaptured `qa/desktop-final.jpg` and `qa/mobile-final.jpg`; reference and updated desktop were inspected together, including the title, description, CTA, version and phone boundary. Typography, colors, copy, assets and the selected still-life composition remain intact. No console errors or warnings. Previous comparison board documents earlier iterations; this iteration's paired reference/capture review supersedes its spacing result.

final result: passed
