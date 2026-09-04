package server

import (
	"log"

	"switchboard/backend/internal/protocol"
)

// dispatch executes one decrypted command and replies on the same connection.
//
// A state-changing command also broadcasts, so a second phone and the desktop
// UI observe the change rather than drifting out of sync.
func (s *Server) dispatch(c *client, env *protocol.Envelope) {
	switch env.Action {

	case protocol.ActionPing:
		s.reply(c, env, map[string]string{"status": "ok"})

	case protocol.ActionDisplayList:
		displays, err := s.control.Displays()
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, displays)

	case protocol.ActionDisplayBrightness, protocol.ActionDisplayContrast:
		var req protocol.DisplaySet
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		set := s.control.SetBrightness
		if env.Action == protocol.ActionDisplayContrast {
			set = s.control.SetContrast
		}
		display, err := set(req.DisplayID, req.Value)
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, display)
		s.Broadcast()

	case protocol.ActionVolumeGet:
		volume, err := s.control.Volume()
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, volume)

	case protocol.ActionVolumeSet:
		var req protocol.Volume
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		volume, err := s.control.SetVolume(req.Level, req.Muted)
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, volume)
		s.Broadcast()

	case protocol.ActionMediaCommand:
		var req protocol.MediaCommand
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.control.Media(req.Action); err != nil {
			s.fail(c, env, err)
			return
		}
		// No broadcast here: the player needs a moment to act on the command,
		// so a snapshot taken now would still carry the old status. The media
		// watcher picks the real change up on its next pass.
		s.reply(c, env, map[string]string{"action": req.Action})

	case protocol.ActionMediaArtwork:
		artwork, err := s.control.MediaArtwork()
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, artwork)

	default:
		c.send(protocol.Errorf(env.ID, env.Action, "unknown action"))
	}
}

func (s *Server) reply(c *client, req *protocol.Envelope, payload any) {
	env, err := protocol.Reply(req.ID, req.Action, payload)
	if err != nil {
		log.Printf("reply %s: %v", req.Action, err)
		return
	}
	c.send(env)
}

func (s *Server) fail(c *client, req *protocol.Envelope, err error) {
	log.Printf("device %q: %s failed: %v", c.deviceID, req.Action, err)
	c.send(protocol.Errorf(req.ID, req.Action, err.Error()))
}
