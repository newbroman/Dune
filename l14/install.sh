#!/usr/bin/env bash
# install.sh: put l14-status on the L14 (or update it) and print what Dune needs.
#   curl -fsSL https://raw.githubusercontent.com/newbroman/Dune/main/l14/install.sh | bash
# Installs ~/bin/l14-status.py and the user service l14-status.service (TCP 8714, token-protected).
# Needs no sudo. Re-running it updates the script and restarts the service; the token is kept.
set -euo pipefail
BASE="https://raw.githubusercontent.com/newbroman/Dune/main/l14"
mkdir -p "$HOME/bin" "$HOME/.config/systemd/user"
curl -fsSL "$BASE/l14-status.py" -o "$HOME/bin/l14-status.py"
chmod +x "$HOME/bin/l14-status.py"
curl -fsSL "$BASE/l14-status.service" -o "$HOME/.config/systemd/user/l14-status.service"
systemctl --user daemon-reload
systemctl --user enable l14-status.service >/dev/null
systemctl --user restart l14-status.service
sleep 2
systemctl --user is-active --quiet l14-status.service \
  || { echo "l14-status did not start:"; journalctl --user -u l14-status.service -n 20 --no-pager; exit 1; }

echo
python3 "$HOME/bin/l14-status.py" --no-tasks
echo
echo "== For Dune (Settings > L14) =="
TS=$(tailscale ip -4 2>/dev/null | head -1 || true)
echo "Address:      ${TS:-<Tailscale not set up yet - see the README>}"
echo "Status port:  8714"
echo "Status token: $(python3 "$HOME/bin/l14-status.py" token)"
if command -v ufw >/dev/null && sudo -n ufw status 2>/dev/null | grep -q "Status: active"; then
  echo
  echo "The firewall is on. Let the phone in over Tailscale with:"
  echo "  sudo ufw allow in on tailscale0 to any port 8714 proto tcp"
fi
