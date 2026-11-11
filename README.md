# March Helper (Evony overlay)

A floating bubble for Android that opens a troop-counter chart and a march
layering calculator on top of Evony: The King's Return. It only shows
information. It doesn't read the screen and it doesn't tap the game.

## What's inside

- `app/src/main/assets/index.html`: the whole UI (counters, layers,
  defense). You can open it in any browser to test.
- `OverlayService.java`: the draggable bubble, the floating panel and the
  drag-to-close target.
- `MainActivity.java`: the setup screen (permission plus Start/Stop).

## Get the APK (no Android Studio needed)

1. Create a free GitHub account and a new **empty** repository.
2. Upload every file in this folder to it, keeping the folders the same
   (`.github/workflows/build-apk.yml` included).
3. Open the **Actions** tab. The "Build APK" job runs on its own, or you can
   start it with **Run workflow**.
4. When the job turns green, open **Releases** on your phone, download
   `MarchHelper.apk` and install it. Android will ask you to allow installs
   from your browser.

You can also open this folder in Android Studio and press Run.

## Using it

1. Open March Helper, then tap **Allow display over other apps** and turn
   it on.
2. Tap **Start overlay**. A gold ⚔ bubble appears.
3. Open Evony. Tap the bubble to open or close the panel. Drag the header
   to move the panel, and use ◐ to make it see-through.
4. To close the overlay, drag the bubble onto the ✕ at the bottom, or tap
   Stop in the notification.

## Data sources and caveats

- Counter triangle (Mounted > Ground > Ranged > Mounted; Siege strong vs
  Ranged), plus range and speed: Evony Guide Wiki troop-type guide.
- Damage multipliers are community-reported and not confirmed by Evony.
- Layer splits come from published TKR layering guides (for example, a
  1M Ranged march at roughly 400K / 300K / 150K across the top three tiers).
  Treat them as starting points.
- Evony's terms ban unauthorized third-party software. This app is a
  passive reference panel, which is the lowest-risk kind of tool, but
  there's still some risk.

## Ideas for later

- Saved presets (PvP Ranged, Ground, Mounted) with one-tap recall.
- An OCR "read my march size" button. This carries higher terms-of-service
  risk.
