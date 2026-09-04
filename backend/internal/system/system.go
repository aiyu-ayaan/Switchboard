package system

// Controller manages OS-level operations (brightness, volume, media, DDC/CI)
type Controller struct{}

func NewController() *Controller {
	return &Controller{}
}
