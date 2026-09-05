//go:build !windows

package camera

// VCamFeeder is a stub for non-Windows platforms.
type VCamFeeder struct{}

func NewVCamFeeder() *VCamFeeder {
	return &VCamFeeder{}
}

func (v *VCamFeeder) Feed(jpegBytes []byte) error {
	return nil
}

func (v *VCamFeeder) Close() {}

func IsVCamInstalled() bool {
	return false
}
