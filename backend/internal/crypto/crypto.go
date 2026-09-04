package crypto

// EncryptionService handles end-to-end encryption and device key exchange
type EncryptionService struct{}

func NewEncryptionService() *EncryptionService {
	return &EncryptionService{}
}
