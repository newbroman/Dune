#!/data/data/com.termux/files/usr/bin/bash
# l14-setup.sh: set Termux up so one tap in Dune gets you a shell on the L14 (run once, in Termux).
#
# Dune's "set up Termux for the L14" runs this for you. By hand:
#   curl -fsSL https://raw.githubusercontent.com/newbroman/Dune/main/l14/termux/l14-setup.sh | L14_HOST=<address> bash
#
# Settings come in as environment variables (Dune fills them from its L14 settings):
#   L14_HOST       the L14's address (IP, Tailscale 100.x address or MagicDNS name)   required the first time
#   L14_USER       user on the L14 (default john)
#   L14_PORT       ssh port (default 22)
#   TS_APP         1 if the Tailscale Android app is installed on this phone (default 0)
#   L14_TAILSCALE  termux | no | auto (default auto: Tailscale inside Termux unless the Android app is there)
#   L14_MOSH       yes | no (default yes: use mosh when the L14 has mosh-server, else ssh)
#   L14_LOCAL      1 when ~/bin/l14 was just written by Dune (otherwise the latest is fetched from GitHub)
#
# It installs openssh, tmux, mosh (and tailscale when wanted), writes ~/bin/l14 (the connect script),
# ~/.config/dune/l14.conf, a "Host l14" entry in ~/.ssh/config with keepalives, an ssh key if you have
# none, turns on allow-external-apps so Dune can run commands in Termux, and finishes by putting your
# key on the L14 if it can reach it. Safe to run again. Nothing here holds secrets.
set -u
main() {
  local BASE="https://raw.githubusercontent.com/newbroman/Dune/main/l14/termux"
  local CONF_DIR="$HOME/.config/dune" CONF="$HOME/.config/dune/l14.conf"
  mkdir -p "$HOME/bin" "$CONF_DIR" "$HOME/.ssh" "$HOME/.termux" "$HOME/.shortcuts"
  chmod 700 "$HOME/.ssh"

  # ---- settings: new values win, old ones are kept
  # shellcheck disable=SC1090
  [ -f "$CONF" ] && . "$CONF"
  local HOST=${L14_HOST:-${HOST:-}} USER_=${L14_USER:-${USER_:-john}} PORT=${L14_PORT:-${PORT:-22}}
  local TS_APP=${TS_APP:-0} MOSH TS_WANT=${L14_TAILSCALE:-auto}
  case "${L14_MOSH:-yes}" in no|0|false) MOSH=no ;; *) MOSH=auto ;; esac
  if [ -z "$HOST" ]; then
    echo "l14-setup: no address. Run it as:  L14_HOST=<the L14's address> bash l14-setup.sh" >&2
    exit 2
  fi
  cat >"$CONF" <<EOF
# written by l14-setup.sh (Dune); l14 reads this
HOST='$HOST'
USER_='$USER_'
PORT='$PORT'
TS_APP='$TS_APP'
MOSH='$MOSH'
EOF
  echo "== L14: $USER_@$HOST:$PORT (saved in $CONF)"

  # ---- packages (stdin closed so a piped run can't be eaten by apt)
  echo "== packages"
  pkg install -y openssh tmux termux-tools </dev/null || { echo "pkg install failed – try: pkg update, then run this again" >&2; exit 1; }
  pkg install -y mosh </dev/null || echo "   (mosh not installed – ssh will be used)"
  local ts_termux=no
  case "$TS_WANT" in
    termux) ts_termux=yes ;;
    no) ts_termux=no ;;
    *) if command -v tailscale >/dev/null 2>&1; then ts_termux=yes; elif [ "$TS_APP" = 1 ]; then ts_termux=no; else ts_termux=yes; fi ;;
  esac
  if [ "$ts_termux" = yes ]; then
    pkg install -y tailscale </dev/null || echo "   (tailscale not installed – install the Tailscale app instead)"
  fi

  # ---- the connect script
  if [ "${L14_LOCAL:-0}" != 1 ] || [ ! -f "$HOME/bin/l14" ]; then
    echo "== fetching ~/bin/l14"
    if curl -fsSL "$BASE/l14" -o "$HOME/bin/l14.new"; then mv "$HOME/bin/l14.new" "$HOME/bin/l14"
    else echo "couldn't fetch $BASE/l14" >&2; [ -f "$HOME/bin/l14" ] || exit 1
    fi
  fi
  chmod +x "$HOME/bin/l14"
  # shellcheck disable=SC2016
  printf '#!/data/data/com.termux/files/usr/bin/bash\nexec "$HOME/bin/l14" "$@"\n' >"$HOME/.shortcuts/L14"
  chmod +x "$HOME/.shortcuts/L14"
  # shellcheck disable=SC2016
  case ":$PATH:" in *":$HOME/bin:"*) ;; *) grep -q 'HOME/bin' "$HOME/.bashrc" 2>/dev/null || printf '\nexport PATH="$HOME/bin:$PATH"\n' >>"$HOME/.bashrc" ;; esac

  # ---- let Dune run commands in Termux
  local tp="$HOME/.termux/termux.properties"
  touch "$tp"
  if grep -qE '^[[:space:]]*#?[[:space:]]*allow-external-apps' "$tp"; then
    sed -i -E 's/^[[:space:]]*#?[[:space:]]*allow-external-apps.*/allow-external-apps = true/' "$tp"
  else
    printf '\nallow-external-apps = true\n' >>"$tp"
  fi
  termux-reload-settings >/dev/null 2>&1 || true
  echo "== allow-external-apps = true (Dune can run commands here)"

  # ---- ssh: a key, and "ssh l14" with keepalives
  if [ ! -f "$HOME/.ssh/id_ed25519" ] && [ ! -f "$HOME/.ssh/id_rsa" ]; then
    echo "== making an ssh key"
    ssh-keygen -t ed25519 -N "" -f "$HOME/.ssh/id_ed25519" </dev/null >/dev/null
  fi
  local sc="$HOME/.ssh/config" tmp
  touch "$sc"; chmod 600 "$sc"
  tmp=$(mktemp)
  sed '/^# >>> dune l14 >>>$/,/^# <<< dune l14 <<<$/d' "$sc" >"$tmp"
  cat >>"$tmp" <<EOF
# >>> dune l14 >>>
Host l14
    HostName $HOST
    User $USER_
    Port $PORT
    ServerAliveInterval 15
    ServerAliveCountMax 4
    TCPKeepAlive yes
    ConnectTimeout 20
# <<< dune l14 <<<
EOF
  mv "$tmp" "$sc"; chmod 600 "$sc"
  echo "== ~/.ssh/config: Host l14 → $USER_@$HOST:$PORT with keepalives"

  # ---- put the key on the L14 if it answers now (asks the L14 password once)
  if timeout 3 bash -c "exec 3<>/dev/tcp/$HOST/$PORT" 2>/dev/null; then
    if ssh -o BatchMode=yes -o ConnectTimeout=8 -p "$PORT" "$USER_@$HOST" true 2>/dev/null; then
      echo "== the L14 already accepts this phone's key"
    else
      echo "== copying your key to the L14 (its password, once):"
      ssh-copy-id -p "$PORT" "$USER_@$HOST" </dev/null || echo "   (not done – run later:  ssh-copy-id -p $PORT $USER_@$HOST)"
    fi
  else
    echo "== $HOST isn't reachable right now; the first 'l14' will bring Tailscale up and may ask the L14's password"
  fi

  cat <<EOF

== done. Tap "Connect" in Dune's L14 section, or type:  l14

Still to do, once:
 * Termux's notification (pull it down): tap "Acquire wakelock".  l14 does this too while it runs.
 * Dune › L14 › "keep Termux alive": take Termux (and Tailscale) off battery optimisation.
 * On the L14, with the phone plugged in by USB (USB debugging on) – stops Android 12+ killing
   Termux's background processes:
     adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
     adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
     adb shell "settings put global settings_enable_monitor_phantom_procs false"
     adb shell "dumpsys deviceidle whitelist +com.termux"
 * On the L14 (for mosh, which survives the phone sleeping; otherwise ssh + tmux is used):
     sudo apt install -y mosh tmux
     sudo ufw allow in on tailscale0 to any port 60000:61000 proto udp   # only if ufw is on
EOF
  if [ "$ts_termux" = yes ]; then
    echo " * Tailscale runs inside Termux: the first 'l14' opens the login page for you."
  fi
}
main "$@"
