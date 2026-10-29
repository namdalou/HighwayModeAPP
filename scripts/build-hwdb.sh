#!/usr/bin/env bash
# PC で全国データを作る（osmium-tool と JDK 17 が必要）
#   macOS:  brew install osmium-tool
#   Ubuntu: sudo apt install osmium-tool
# 使い方: scripts/build-hwdb.sh [出力ファイル]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-app/src/main/assets/hwdb/japan-highways.hwdb}
WORK=build/hwdb-work
mkdir -p "$WORK" "$(dirname "$OUT")"
if [ ! -f "$WORK/japan.osm.pbf" ]; then
  curl -fL --retry 5 -o "$WORK/japan.osm.pbf" https://download.geofabrik.de/asia/japan-latest.osm.pbf
fi
osmium tags-filter "$WORK/japan.osm.pbf" \
  w/highway=motorway,motorway_link,trunk,trunk_link \
  w/toll=yes w/motorroad=yes \
  nwr/highway=services,rest_area \
  n/highway=motorway_junction,speed_camera n/barrier=toll_booth \
  nwr/amenity=fuel,charging_station,restaurant,fast_food,food_court,cafe,toilets,shower,atm,bank,information \
  nwr/shop nwr/tourism=information,hotel,motel,guest_house nwr/leisure=dog_park \
  -o "$WORK/filtered.osm.pbf" --overwrite
osmium add-locations-to-ways "$WORK/filtered.osm.pbf" -o "$WORK/located.osm.gz" -f osm.gz,locations_on_ways=true --overwrite
./gradlew -q :datatool:run --args="osm --in $PWD/$WORK/located.osm.gz --out $PWD/$OUT"
echo "作成しました: $OUT"
