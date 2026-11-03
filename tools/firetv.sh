#!/usr/bin/env bash
# The Fire TV setup that only a computer can do, once (Fire OS's adbd never answers the TV itself, so the app can't):
# Amazon's extra apps off, our home hook on (accessibility on Fire OS 7/8; UsageStats on Fire OS 6, which refuses
# third-party accessibility services), our screensaver, music access for the screensaver, and the TV listings
# permission for the picks. `undo` puts it all back.
#
#   tools/firetv.sh <ip> setup    # after: adb install -r IdiotBox.apk
#   tools/firetv.sh <ip> undo     # back to Amazon's own; then the app can be uninstalled cleanly
#   tools/firetv.sh <ip> status
#   tools/firetv.sh <ip> off <package>   # one of OPTIONAL, only when the person asks
#
# Apps are only turned off (pm disable-user), never uninstalled: undo turns them back on.
set -euo pipefail

usage() { echo "Usage: tools/firetv.sh <ip> setup|undo|status, or tools/firetv.sh <ip> off <package>" >&2; exit 2; }
[ $# -eq 2 ] || { [ $# -eq 3 ] && [ "$2" = off ]; } || usage
IP="$1"
ACTION="$2"
PKG=io.idiotbox
SERVICE="$PKG/$PKG.FireTvService"
LISTENER="$PKG/$PKG.NowPlaying"
AMAZON_DREAM=com.amazon.ftv.screensaver/.app.services.ScreensaverService

# Amazon's extras, off in Idiot Box mode. Its launcher, ad service and most telemetry are protected (pm refuses them).
DEBLOAT=(
  com.amazon.hybridadidservice com.amazon.sneakpeek com.amazon.tv.acr   # ad id, sneak peeks, screen-content recognition (ACR)
  com.amazon.shoptv.client com.amazon.shoptv.firetv.client              # shopping
  com.amazon.media.recommendations com.amazon.tv.easyupgrade            # promo rows, upgrade nags
  com.amazon.kso.blackbird com.amazon.tv.website_launcher               # ad landing pages
  com.amazon.minitv.android.app com.amazon.gamehub com.amazon.avls.experience   # miniTV, Game Hub, promos
  com.amazon.bueller.music com.amazon.bueller.photos com.amazon.cloud9  # Music, Photos, Silk browser
  com.amazon.tahoe com.amazon.ods.kindleconnect com.amazon.ftv.screensaver      # Kids, Kindle, screensaver
  com.amazon.storm.lightning.tutorial com.amazon.tmm.tutorial com.amazon.tv.releasenotes
  com.amazon.tv.livetv                                                  # Live TV: its button opens ours (LiveTvActivity)
  com.amazon.aca com.amazon.ceviche com.amazon.whisperplay.service.install
  com.amazon.tv.matter com.amazon.tv.mattercohost                       # smart home
  com.amazon.tv.alexaalerts com.amazon.tv.alexanotifications            # Alexa
  com.amazon.vizzini com.amazon.vizzini.ftvcds                          # Alexa assistant (back at every boot)
  com.amazon.alexa.datastore.app com.amazon.alexa.externalmediaplayer.fireos
  com.amazon.alexadirectivebrokerservice com.amazon.alexamediaplayer.runtime.ftv
  com.amazon.firetv.androidskillconnectioncapability
  com.amazon.whad com.amazon.whasettings com.amazon.wha.mediabrowserservice com.amazon.audiohome   # multi-room audio
  com.amazon.tv.fw.metrics com.amazon.wirelessmetrics.service           # telemetry (back at every boot)
  com.amazon.dp.logger com.amazon.perfc com.amazon.perfcollection com.amazon.neo.minerva.logcat
)

# Off only when the person asks (off <package>), as they're apps someone may use; undo turns them back on too.
OPTIONAL=(
  com.amazon.firebat   # Prime Video: wakes at every Home press for Amazon's rows and ads (~140 MB on a 1 GB stick)
)

adb connect "$IP:5555" >/dev/null
sh() { adb -s "$IP:5555" shell "$@" | tr -d '\r'; }

[ "$(sh getprop ro.product.manufacturer)" = Amazon ] || { echo "$IP is not a Fire TV." >&2; exit 1; }
if [ "$ACTION" = setup ] && ! sh pm path "$PKG" | grep -q package:; then
  echo "Install the app first: adb install -r IdiotBox.apk" >&2
  exit 1
fi
# Fire OS 8 (Android 11) runs the screensaver in the main process (ScreensaverAlone).
SDK="$(sh getprop ro.build.version.sdk)"
if [ "$SDK" = 30 ]; then DREAM="$PKG/.ScreensaverAlone"; else DREAM="$PKG/.Screensaver"; fi

# Android 7.1 (Fire OS 6) has no `cmd notification`: there the listener lives in the secure setting, which the
# notification service follows (it rebinds on the change).
music_access() {
  local cur kept
  if [ "$1" = on ]; then
    if [ "$SDK" -le 25 ]; then
      cur="$(sh settings get secure enabled_notification_listeners)"
      [ "$cur" = null ] && cur=
      if tr ':' '\n' <<<"$cur" | grep -qx -e "$LISTENER"; then return 0; fi
      sh settings put secure enabled_notification_listeners "${cur:+$cur:}$LISTENER"
    else
      sh cmd notification allow_listener "$LISTENER"
    fi
  elif [ "$SDK" -le 25 ]; then
    cur="$(sh settings get secure enabled_notification_listeners)"
    [ "$cur" = null ] && cur=
    kept="$(tr ':' '\n' <<<"$cur" | grep -vx -e "$LISTENER" -e "$PKG/.NowPlaying" | grep -v '^$' | paste -sd: - || true)"
    if [ -n "$kept" ]; then sh settings put secure enabled_notification_listeners "$kept"
    else sh settings delete secure enabled_notification_listeners >/dev/null; fi
  else
    sh cmd notification disallow_listener "$LISTENER"
  fi
}

installed="$(sh pm list packages | sed 's/^package://')"
disabled="$(sh pm list packages -d | sed 's/^package://')"
has() { grep -qx "$1" <<<"$2"; }

# The enabled accessibility services without ours (keeping TalkBack and the like). Android may store ours in the
# short form too (io.idiotbox/.FireTvService).
others() {
  local v; v="$(sh settings get secure enabled_accessibility_services)"
  [ "$v" = null ] && v=
  tr ':' '\n' <<<"$v" | grep -vx -e "$SERVICE" -e "$PKG/.FireTvService" | grep -v '^$' | paste -sd: - || true
}

case "$ACTION" in
  setup)
    for p in "${DEBLOAT[@]}"; do
      if has "$p" "$installed" && ! has "$p" "$disabled"; then
        if sh pm disable-user --user 0 "$p" | grep -q "disabled-user"; then echo "off: $p"; else echo "refused: $p"; fi
      fi
    done
    rest="$(others)"
    if [ "$SDK" -le 27 ]; then
      # Fire OS 6 refuses third-party accessibility services; FireTvKeeper reads the screen in front from UsageStats
      # instead. It starts when the mode switches to Idiot Box (and at boot), and checks this grant on every poll, so
      # granting it now is enough.
      sh appops set "$PKG" GET_USAGE_STATS allow 2>/dev/null || true
    else
      sh settings put secure enabled_accessibility_services "${rest:+$rest:}$SERVICE"
      sh settings put secure accessibility_enabled 1
    fi
    music_access on
    sh settings put secure screensaver_components "$DREAM"
    sh pm grant "$PKG" android.permission.READ_TV_LISTINGS 2>/dev/null || true
    echo "Done. On the TV: open Idiot Box > Home Screen > Idiot Box."
    ;;
  undo)
    for p in "${DEBLOAT[@]}" "${OPTIONAL[@]}"; do
      if has "$p" "$disabled"; then sh pm enable "$p" >/dev/null && echo "on: $p"; fi
    done
    rest="$(others)"
    if [ -n "$rest" ]; then sh settings put secure enabled_accessibility_services "$rest"
    else sh settings delete secure enabled_accessibility_services >/dev/null; fi
    music_access off
    sh appops set "$PKG" GET_USAGE_STATS default 2>/dev/null || true
    sh settings put secure screensaver_components "$AMAZON_DREAM"
    echo "Done: Amazon's apps, home screen and screensaver are back. To remove the app: adb uninstall $PKG"
    ;;
  status)
    if [ "$SDK" -le 27 ]; then echo "accessibility: not used (Fire OS 6 uses UsageStats)"
    else echo "accessibility: $(sh settings get secure enabled_accessibility_services)"; fi
    echo "usage access:  $(sh appops get "$PKG" GET_USAGE_STATS 2>/dev/null)"
    echo "music access:  $(sh settings get secure enabled_notification_listeners)"
    echo "screensaver:   $(sh settings get secure screensaver_components)"
    off=0; for p in "${DEBLOAT[@]}"; do has "$p" "$disabled" && off=$((off + 1)); done
    echo "Amazon extras off: $off of ${#DEBLOAT[@]} listed"
    ;;
  off)
    p="$3"
    printf '%s\n' "${OPTIONAL[@]}" | grep -qx "$p" || { echo "$p isn't in OPTIONAL (see the top of this script)." >&2; exit 2; }
    if has "$p" "$disabled"; then echo "already off: $p"
    elif ! has "$p" "$installed"; then echo "not installed: $p"
    elif sh pm disable-user --user 0 "$p" | grep -q "disabled-user"; then echo "off: $p (undo turns it back on)"
    else echo "refused: $p"; fi
    ;;
  *) usage ;;
esac
