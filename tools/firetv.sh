#!/usr/bin/env bash
# The Fire TV setup that only a computer can do, once (Fire OS's adbd never answers the TV itself, so the app can't):
# Amazon's extra apps off, our accessibility service on (it opens our home screen over Amazon's), our screensaver,
# music access for the screensaver, and the TV listings permission for the picks. Alexa is off by default: it costs
# a noticeable share of a 1 GB stick's RAM. `undo` puts it all back.
#
#   tools/firetv.sh <ip> setup    # after: adb install -r IdiotBox.apk
#   tools/firetv.sh <ip> undo     # back to Amazon's own; then the app can be uninstalled cleanly
#   tools/firetv.sh <ip> status
#   tools/firetv.sh <ip> alexa on|off    # voice search; off by default
#   tools/firetv.sh <ip> off <package>   # one of OPTIONAL, only when the person asks
#
# Apps are only turned off (pm disable-user), never uninstalled: undo turns them back on.
set -euo pipefail

usage() { echo "Usage: tools/firetv.sh <ip> setup|undo|status|alexa on|off, or tools/firetv.sh <ip> off <package>" >&2; exit 2; }
[ $# -eq 2 ] || { [ $# -eq 3 ] && { [ "$2" = off ] || [ "$2" = alexa ]; }; } || usage
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
  com.amazon.ceviche com.amazon.whisperplay.service.install
  com.amazon.tv.matter com.amazon.tv.mattercohost                       # smart home
  com.amazon.tv.fw.metrics com.amazon.wirelessmetrics.service           # telemetry (back at every boot)
  com.amazon.dp.logger com.amazon.perfc com.amazon.perfcollection com.amazon.neo.minerva.logcat
)

# Alexa is off by default: it costs a noticeable share of a 1 GB stick's RAM. `alexa on` turns voice search on;
# `alexa off` (and every setup) turns it off again.
ALEXA=(
  com.amazon.vizzini com.amazon.vizzini.ftvcds com.amazon.aca
  com.amazon.alexa.datastore.app com.amazon.alexa.externalmediaplayer.fireos
  com.amazon.alexadirectivebrokerservice com.amazon.alexamediaplayer.runtime.ftv
  com.amazon.firetv.androidskillconnectioncapability
  com.amazon.tv.alexaalerts com.amazon.tv.alexanotifications
  com.amazon.whad com.amazon.whasettings com.amazon.wha.mediabrowserservice com.amazon.audiohome
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
if [ "$(sh getprop ro.build.version.sdk)" = 30 ]; then DREAM="$PKG/.ScreensaverAlone"; else DREAM="$PKG/.Screensaver"; fi

installed="$(sh pm list packages | sed 's/^package://')"
disabled="$(sh pm list packages -d | sed 's/^package://')"
has() { grep -qx "$1" <<<"$2"; }

# Alexa's packages, on or off; only the ones this Fire OS has.
alexa() {
  for p in "${ALEXA[@]}"; do
    has "$p" "$installed" || continue
    if [ "$1" = on ]; then
      if has "$p" "$disabled"; then
        if sh pm enable "$p" >/dev/null; then echo "on: $p"; fi
      fi
    elif ! has "$p" "$disabled"; then
      if sh pm disable-user --user 0 "$p" | grep -q "disabled-user"; then echo "off: $p"; else echo "refused: $p"; fi
    fi
  done
}

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
    # Voice search costs RAM: off unless the person asks for it (tools/firetv.sh <ip> alexa on).
    alexa off
    rest="$(others)"
    sh settings put secure enabled_accessibility_services "${rest:+$rest:}$SERVICE"
    sh settings put secure accessibility_enabled 1
    sh cmd notification allow_listener "$LISTENER"
    sh settings put secure screensaver_components "$DREAM"
    sh pm grant "$PKG" android.permission.READ_TV_LISTINGS 2>/dev/null || true
    echo "Done. On the TV: open Idiot Box > Home Screen > Idiot Box."
    ;;
  undo)
    for p in "${DEBLOAT[@]}" "${OPTIONAL[@]}" "${ALEXA[@]}"; do
      if has "$p" "$disabled"; then sh pm enable "$p" >/dev/null && echo "on: $p"; fi
    done
    rest="$(others)"
    if [ -n "$rest" ]; then sh settings put secure enabled_accessibility_services "$rest"
    else sh settings delete secure enabled_accessibility_services >/dev/null; fi
    sh cmd notification disallow_listener "$LISTENER"
    sh settings put secure screensaver_components "$AMAZON_DREAM"
    echo "Done: Amazon's apps, home screen and screensaver are back. To remove the app: adb uninstall $PKG"
    ;;
  status)
    echo "accessibility: $(sh settings get secure enabled_accessibility_services)"
    echo "music access:  $(sh settings get secure enabled_notification_listeners)"
    if has com.amazon.vizzini "$disabled"; then echo "alexa:         off"; else echo "alexa:         on"; fi
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
  alexa)
    case "$3" in
      on|off) alexa "$3" ;;
      *) usage ;;
    esac
    ;;
  *) usage ;;
esac
