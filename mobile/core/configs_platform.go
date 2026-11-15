package core

// What sing-box asks of the platform it runs on, answered with "nothing".
//
// On Android sing-box expects the app to hand it a long list of the phone's
// services - the tun, the network monitor, Wi-Fi state, notifications. Here
// it is a library behind this core's own tun (configs_box.go), so it needs
// none of them, and asking Android for them would mean netlink sockets an
// app is no longer allowed. So every question is answered here, in Go, with
// the plain no: no tun of its own, no interface monitoring, no binding.
//
// Its sockets need no protecting either: the app leaves itself out of its
// own VPN in every split mode (MirageVpnService.splitInto), so nothing this
// process dials falls back into the tunnel it is carrying.

import (
	"context"
	"errors"
	"net/netip"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

var errNotHere = errors.New("not available inside Mirage")

type platformStub struct{}

var _ adapter.PlatformInterface = platformStub{}

func (platformStub) Initialize(adapter.NetworkManager) error { return nil }

func (platformStub) UsePlatformAutoDetectInterfaceControl() bool { return false }
func (platformStub) AutoDetectInterfaceControl(int) error        { return nil }

func (platformStub) UsePlatformInterface() bool { return false }
func (platformStub) OpenInterface(*tun.Options, option.TunPlatformOptions) (tun.Tun, error) {
	return nil, errNotHere
}
func (platformStub) ProcessPlatformOptions(option.TunPlatformOptions) error { return nil }

func (platformStub) UsePlatformDefaultInterfaceMonitor() bool { return true }
func (platformStub) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	return quietMonitor{}
}

func (platformStub) UsePlatformNetworkInterfaces() bool                     { return false }
func (platformStub) NetworkInterfaces() ([]adapter.NetworkInterface, error) { return nil, nil }

func (platformStub) UnderNetworkExtension() bool              { return false }
func (platformStub) NetworkExtensionIncludeAllNetworks() bool { return false }

func (platformStub) ClearDNSCache()                       {}
func (platformStub) RequestPermissionForWIFIState() error { return nil }
func (platformStub) ReadWIFIState(context.Context) adapter.WIFIState {
	return adapter.WIFIState{}
}

func (platformStub) UsePlatformConnectionOwnerFinder() bool { return false }
func (platformStub) FindConnectionOwner(*adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, errNotHere
}

func (platformStub) UsePlatformWIFIMonitor() bool { return false }

func (platformStub) UsePlatformNotification() bool                             { return false }
func (platformStub) SendNotification(*adapter.Notification) error              { return nil }
func (platformStub) CancelNotification(string, int32) error                    { return nil }
func (platformStub) MyInterfaceAddress() []netip.Addr                          { return nil }
func (platformStub) UsePlatformNeighborResolver() bool                         { return false }
func (platformStub) StartNeighborMonitor(adapter.NeighborUpdateListener) error { return nil }
func (platformStub) CloseNeighborMonitor(adapter.NeighborUpdateListener) error { return nil }

func (platformStub) UsePlatformShell() bool    { return false }
func (platformStub) CheckPlatformShell() error { return errNotHere }
func (platformStub) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, errNotHere
}
func (platformStub) LookupUser(string) (*adapter.PlatformUser, error) { return nil, errNotHere }
func (platformStub) LookupSFTPServer() (string, error)                { return "", errNotHere }
func (platformStub) ReadSystemSSHHostKey() ([]byte, error)            { return nil, errNotHere }
func (platformStub) TailscaleHostname() string                        { return "" }

func (platformStub) UsePlatformBridge() bool { return false }
func (platformStub) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, errNotHere
}

// quietMonitor watches nothing: there is no interface for sing-box to bind
// to, because nothing it dials is bound.
type quietMonitor struct{}

func (quietMonitor) Start() error                         { return nil }
func (quietMonitor) Close() error                         { return nil }
func (quietMonitor) DefaultInterface() *control.Interface { return nil }
func (quietMonitor) OverrideAndroidVPN() bool             { return false }
func (quietMonitor) AndroidVPNEnabled() bool              { return false }
func (quietMonitor) RegisterCallback(tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}
func (quietMonitor) UnregisterCallback(*list.Element[tun.DefaultInterfaceUpdateCallback]) {}
func (quietMonitor) RegisterMyInterface(string)                                           {}
func (quietMonitor) MyInterfaces() []string                                               { return nil }
