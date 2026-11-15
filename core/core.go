// Package mikaelacore — тонкая обёртка над Xray-core для Android (gomobile).
// TUN-дескриптор от VpnService передаётся ядру через переменную окружения XRAY_TUN_FD.
package mikaelacore

import (
	"errors"
	"os"
	"strconv"
	"sync"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	_ "golang.org/x/mobile/bind"
)

var (
	mu   sync.Mutex
	inst *core.Instance
)

// Start запускает Xray с JSON-конфигом. assetDir — папка с geoip.dat/geosite.dat, tunFd — fd TUN-интерфейса.
func Start(configJSON string, assetDir string, tunFd int) error {
	mu.Lock()
	defer mu.Unlock()
	if inst != nil {
		_ = inst.Close()
		inst = nil
	}
	os.Setenv("XRAY_LOCATION_ASSET", assetDir)
	os.Setenv("XRAY_TUN_FD", strconv.Itoa(tunFd))
	i, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		return err
	}
	if i == nil {
		return errors.New("xray: пустой экземпляр")
	}
	inst = i
	return nil
}

// Stop останавливает Xray.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if inst != nil {
		_ = inst.Close()
		inst = nil
	}
}

// Version возвращает версию ядра.
func Version() string { return core.Version() }
