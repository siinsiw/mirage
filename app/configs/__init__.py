"""Configs: servers bought or given from anywhere, as vless:// vmess://
trojan:// ss:// hysteria2:// tuic:// links and subscriptions.

A section of its own, with nothing to do with the provider accounts: its own
engine (sing-box, with Xray in front for the transports sing-box does not
have), its own list, its own tests. What it shares with the rest of the app
is the machine - the system proxy, the adapter, the kill switch's firewall
hold, the split lists - and one rule: only one connection is up at a time.

    links    reading links, defensively, into one plain shape
    singbox  that shape as sing-box configuration
    xray     the same for Xray, for xhttp and the few others
    store    what is kept, sealed, on disk
    subs     fetching subscriptions
    probe    latency tests
    runner   bringing it all up and down
"""
