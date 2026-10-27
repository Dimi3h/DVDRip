#!/usr/bin/env bash
# A throw-away Samba server for the SMB tests of core (./gradlew -p core smbTest): share "video"
# for user "discriptest" (password "discrip-test") on 127.0.0.1, port 1445, SMB 2 and 3 only,
# plus share "readonly" that the user may read but not write.
#
#   samba-test-server.sh start <directory>   prints the environment the tests read
#   samba-test-server.sh stop <directory>
#
# Samba touches the files as discriptest, so every folder above <directory> must let other users
# through (/tmp does; a private home or temp folder may not). Needs root (or sudo), smbd and
# smbpasswd (Debian/Ubuntu: apt install samba).
set -euo pipefail

action="${1:?usage: samba-test-server.sh start|stop <directory>}"
dir="$(mkdir -p "${2:?usage: samba-test-server.sh start|stop <directory>}" && cd "$2" && pwd)"
user=discriptest
password=discrip-test
port=1445
as_root() { if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo "$@"; fi; }

case "$action" in
    start)
        # smbd's own folders belong to root; the shares to whoever runs the tests, who empties them.
        as_root mkdir -p "$dir"/{run,lock,state,cache,private,ncalrpc}
        mkdir -p "$dir"/{share,readonly}
        id "$user" > /dev/null 2>&1 || as_root useradd --no-create-home --shell /usr/sbin/nologin "$user"
        chmod 777 "$dir/share"
        chmod 755 "$dir/readonly"
        if ! as_root runuser -u "$user" -- test -w "$dir/share"; then
            echo "User $user cannot reach $dir/share: a folder above it keeps other users out. Use a directory all users can reach, such as one under /tmp." >&2
            exit 1
        fi
        as_root tee "$dir/smb.conf" > /dev/null <<CONF
[global]
    server role = standalone server
    security = user
    map to guest = never
    smb ports = $port
    interfaces = lo
    bind interfaces only = yes
    server min protocol = SMB2_10
    pid directory = $dir/run
    lock directory = $dir/lock
    state directory = $dir/state
    cache directory = $dir/cache
    private dir = $dir/private
    ncalrpc dir = $dir/ncalrpc
    log file = $dir/smbd.log
    load printers = no
    printing = bsd
    printcap name = /dev/null
    disable spoolss = yes
[video]
    path = $dir/share
    read only = no
    valid users = $user
    force user = $user
    # Whatever arrives stays removable by the user running the tests.
    create mask = 0777
    force create mode = 0666
    directory mask = 0777
    force directory mode = 0777
[readonly]
    path = $dir/readonly
    read only = yes
    valid users = $user
CONF
        printf '%s\n%s\n' "$password" "$password" | as_root smbpasswd -c "$dir/smb.conf" -s -a "$user" > /dev/null
        as_root smbd -D -s "$dir/smb.conf"
        for _ in $(seq 50); do
            (exec 3<>"/dev/tcp/127.0.0.1/$port") 2> /dev/null && break
            sleep 0.2
        done
        (exec 3<>"/dev/tcp/127.0.0.1/$port") 2> /dev/null || { as_root cat "$dir/smbd.log" >&2 || true; exit 1; }
        echo "DISCRIP_SMB_HOST=127.0.0.1"
        echo "DISCRIP_SMB_PORT=$port"
        echo "DISCRIP_SMB_SHARE=video"
        echo "DISCRIP_SMB_USER=$user"
        echo "DISCRIP_SMB_PASSWORD=$password"
        echo "DISCRIP_SMB_READONLY_SHARE=readonly"
        echo "DISCRIP_SMB_ROOT=$dir/share"
        ;;
    stop)
        if [ -f "$dir/run/smbd.pid" ]; then as_root kill "$(cat "$dir/run/smbd.pid")" || true; fi
        for _ in $(seq 50); do
            (exec 3<>"/dev/tcp/127.0.0.1/$port") 2> /dev/null || break
            sleep 0.2
        done
        ;;
    *)
        echo "Unknown action $action" >&2
        exit 1
        ;;
esac
