package server

// Server represents the Switchboard communication daemon
type Server struct {
	port int
}

func NewServer(port int) *Server {
	return &Server{port: port}
}
