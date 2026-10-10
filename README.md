# wifimonitor

A small Java program that runs on a Raspberry Pi and logs Wi-Fi internet outages. It checks connectivity over Wi-Fi only, while all other traffic on the Pi stays on ethernet, and writes a timestamped record of every outage to a monthly log file.

## How it works

- Every 3 seconds, the program pings `1.1.1.1` (Cloudflare) and `8.8.8.8` (Google) through the Wi-Fi interface (`ping -I wlan0`). A check fails only if neither host replies.
- After 4 consecutive failed checks, the connection is considered down. The outage start time is taken from the first failed check.
- While the connection is down, a `DOWN` line is written on every check. When a check succeeds again, an `UP` line and the total downtime are written.
- Logs go to `~/outages/outages-YYYY-MM.txt`, with a new file each month. Timestamps are in local Alberta,Canada time (`America/Edmonton`).

### Example log

```
status  timestamp
START 2026-10-09T21:38:22-06:00
DOWN 2026-10-09T21:38:31-06:00
DOWN 2026-10-09T21:38:34-06:00
...
UP 2026-10-09T21:39:44-06:00
---Total downtime = 1m22s ---
```

## Requirements

- Raspberry Pi with both Wi-Fi and ethernet connected (tested on a Pi 4 running Raspberry Pi OS)
- JDK (`sudo apt install default-jdk`)
- Linux: the ping flags used are Linux-specific, so the program does not work on Windows

## Installation

### 1. Build

Copy `Main.java` to the Pi, then compile and package it:

```
javac Main.java
jar cfe wifimonitor.jar Main *.class
sudo mkdir -p /opt/wifimonitor
sudo mv wifimonitor.jar /opt/wifimonitor/
```

### 2. Create the `wifimonitor` command

Create `/usr/local/bin/wifimonitor` containing:

```
#!/bin/sh
exec java -jar /opt/wifimonitor/wifimonitor.jar "$@"
```

Make it executable:

```
sudo chmod +x /usr/local/bin/wifimonitor
```

### 3. Create the log folder

```
mkdir ~/outages
```

The program does not create this folder itself.

### 4. Network setup

**ARP settings.** With ethernet and Wi-Fi on the same subnet, ping replies can come back over the wrong interface and be counted as failures. Create `/etc/sysctl.d/99-arp.conf`:

```
net.ipv4.conf.all.arp_ignore = 1
net.ipv4.conf.all.arp_announce = 2
```

Apply with `sudo sysctl --system`.

**Route priority.** Run `ip route` and confirm the `eth0` default route has a lower metric than `wlan0`, so everything except the monitor's pings uses ethernet.

**Wi-Fi power saving.** Turn it off so the radio doesn't doze and cause false outages:

```
nmcli connection show
sudo nmcli connection modify "<wifi connection>" 802-11-wireless.powersave 2
sudo nmcli connection up "<wifi connection>"
```

Verify with `iw dev wlan0 get power_save`.

**Fixed IPs.** Reserve both of the Pi's addresses (ethernet and Wi-Fi) in the router's DHCP settings.

### 5. Test

SSH into the Pi using its ethernet IP, run `wifimonitor`, and in a second session turn Wi-Fi off for about 40 seconds:

```
nmcli radio wifi off
nmcli radio wifi on
```

Stop the program with Ctrl+C and check that the log shows START, DOWN, UP, and the total downtime.

### 6. Run as a service

Create `/etc/systemd/system/wifimonitor.service`:

```
[Unit]
Description=Wi-Fi downtime monitor
Wants=network-online.target
After=network-online.target

[Service]
ExecStart=/usr/local/bin/wifimonitor
User=admin
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Replace `admin` with your username, then enable and start it:

```
sudo systemctl daemon-reload
sudo systemctl enable --now wifimonitor
```

## Usage

| Task | Command |
|---|---|
| Check status | `systemctl status wifimonitor` |
| View errors | `journalctl -u wifimonitor` |
| Read the log | `cat ~/outages/outages-YYYY-MM.txt` |
| Stop | `sudo systemctl stop wifimonitor` |
| Apply a code update | Rebuild the jar, copy it to `/opt/wifimonitor/`, then `sudo systemctl restart wifimonitor` |

Don't run `wifimonitor` manually while the service is running; two copies would write to the same log.

## Notes and limitations

- **Minimum outage length:** because of the 4-failure threshold, outages shorter than roughly 10–15 seconds are not recorded.
- **Clock after reboot:** the Pi has no battery-backed clock. If it reboots while the internet is down, timestamps may be wrong until it can sync again.
- **Startup:** if Wi-Fi takes a while to connect after boot, a short outage may be logged right after a reboot.
- **Time zone:** `America/Edmonton` is set in the code; change it there for other locations.
