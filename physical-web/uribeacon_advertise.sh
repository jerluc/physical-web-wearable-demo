#!/bin/bash
#
# Copyright 2014 Google Inc. All rights reserved.
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#     http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# uribeacon_advertise - start advertising UriBeacon over Bluetooth 4.0

# Default values for UriBeacon fields

URI_TX_POWER=0
URI_FLAGS="00"
BT_DEVICE="hci0"

while getopts ":f:u:t:i:" optname; do
  case "$optname" in
      t)
        URI_TX_POWER=$OPTARG
        ;;
      f)
	      URI_FLAGS=$OPTARG
        ;;
      u)
        URI_TEXT=$OPTARG
        ;;
      i)
        BT_DEVICE=$OPTARG
        ;;
      ?)
	  echo "Usage: $0 [-f <00|01>] [-u <url-without-http://>] [-t <20|nn>] [-i <dev>]" 1>&2;
	  exit;
          ;;
  esac
done
  
echo "Transmitting UriBeacon with the following fields:"
echo "URI:       http://$URI_TEXT"
echo "FLAGS:     $URI_FLAGS"
echo "TX_POWER:  $URI_TX_POWER"
echo "DEVICE:    $BT_DEVICE"
echo ""

# Convert the text Uri to bytes. Here we always add http:// code 02
URI_BYTES="02 `echo -n $URI_TEXT | hexdump -v -e '/1 "%02X "'`"

# Additional advertising payload service data
AD_FLAGS="02 01 1a"
AD_SERVICE_UUID="03 03 d8 fe"

# Build advertisement payload
AD_SERVICE_DATA_BODY="16 d8 fe $URI_FLAGS `printf "%x" $URI_TX_POWER | rev | cut -c-2 | rev` $URI_BYTES"
AD_SERVICE_DATA_LEN=$(printf '%x' $(echo -n $AD_SERVICE_DATA_BODY | wc -w))
AD_SERVICE_DATA="$AD_SERVICE_DATA_LEN $AD_SERVICE_DATA_BODY"

# This is the advertisement to be broadcast
AD_DATA="$AD_FLAGS $AD_SERVICE_UUID $AD_SERVICE_DATA"

# Pad Advertising Data with 00 to 32 bytes for hcitool
zero_pad () {
  padding=""
  len=$(echo -n $1 | wc -w)
  while (( len < 31 ))
  do
   padding="$padding 00"
   let len=len+1
  done
  echo -n $1 $padding
}

# These are used by the hcitool that requires a length and 00 padding
AD_CMD_LENGTH=$(printf "%x" $(echo -n $AD_DATA | wc -w))
AD_CMD_DATA=$(zero_pad "$AD_DATA")

# Command Bluetooth device up
sudo hciconfig $BT_DEVICE up
# Stop LE advertising
sudo hciconfig $BT_DEVICE noleadv
# Set Advertising Data
sudo hcitool -i $BT_DEVICE cmd 0x08 0x0008 $AD_CMD_LENGTH $AD_CMD_DATA
# Start LE advertising
sudo hciconfig $BT_DEVICE leadv

read -p "Press [Enter] to stop advertsing..."

sudo hciconfig $BT_DEVICE noleadv
sudo hciconfig $BT_DEVICE down
