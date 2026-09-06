package server

import (
	"log"

	"switchboard/backend/internal/protocol"
)

// dispatch executes one decrypted command and replies on the same connection.
//
// A state-changing command also broadcasts, so a second phone and the desktop
// UI observe the change rather than drifting out of sync.
func (s *Server) dispatch(c *client, env *protocol.Envelope, blob []byte) {
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

	case protocol.ActionMixerList:
		sessions, err := s.control.Mixer()
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, sessions)

	case protocol.ActionMixerSet:
		var req protocol.MixerSet
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		sessions, err := s.control.SetSessionVolume(req.SessionID, req.Level, req.Muted)
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, sessions)
		s.Broadcast()

	case protocol.ActionOutputList:
		outputs, err := s.control.Outputs()
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, outputs)

	case protocol.ActionOutputSet:
		var req protocol.OutputSet
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		outputs, err := s.control.SetOutput(req.DeviceID)
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, outputs)
		// The whole audio picture moved with the endpoint: master level and
		// the mixer now belong to a different device.
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

	// Air mouse. These fire many times a second during a drag, so none of
	// them broadcasts and none of them replies on success: the pointer moving
	// on screen is the acknowledgement, and a reply per frame would double the
	// traffic for nothing. Failures still come back, so a client on an
	// unsupported host finds out.
	case protocol.ActionInputMove:
		var req protocol.InputMove
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.control.MoveMouse(req.DX, req.DY); err != nil {
			s.fail(c, env, err)
		}

	case protocol.ActionInputButton:
		var req protocol.InputButton
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.control.MouseButton(req.Button, req.Action); err != nil {
			s.fail(c, env, err)
		}

	case protocol.ActionInputScroll:
		var req protocol.InputScroll
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.control.Scroll(req.DX, req.DY, req.Ctrl); err != nil {
			s.fail(c, env, err)
		}

	case protocol.ActionInputGesture:
		var req protocol.InputGesture
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.control.ShellGesture(req.Name); err != nil {
			s.fail(c, env, err)
		}

	// Wi-Fi camera. Frames are the heaviest thing on the wire and arrive
	// dozens of times a second, so like the air mouse they neither reply nor
	// broadcast: the picture updating is the acknowledgement.
	case protocol.ActionCameraFrame:
		var meta protocol.CameraFrame
		if err := env.Decode(&meta); err != nil {
			s.fail(c, env, err)
			return
		}
		s.camera.Frame(c.deviceID, meta, blob)

	case protocol.ActionCameraState:
		var state protocol.CameraState
		if err := env.Decode(&state); err != nil {
			s.fail(c, env, err)
			return
		}
		s.camera.ReportState(c.deviceID, state)

	// File transfer. These frames are relayed straight into the transfer
	// manager, which owns all the state; the daemon replies only when the
	// engine refuses outright, because the real answer to an offer or a chunk
	// is another frame the engine sends itself.
	case protocol.ActionFileOffer:
		var req protocol.FileOffer
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		req.Direction = protocol.DirectionUpload
		if err := s.transfers.Offer(c.deviceID, req); err != nil {
			s.fail(c, env, err)
		}

	case protocol.ActionFileAccept:
		var req protocol.FileAccept
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.transfers.Accept(req); err != nil {
			s.fail(c, env, err)
		}

	case protocol.ActionFileChunk:
		var req protocol.FileChunk
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.transfers.Chunk(req, blob); err != nil {
			s.fail(c, env, err)
		}

	case protocol.ActionFileAck:
		var req protocol.FileAck
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		s.transfers.Ack(req)

	case protocol.ActionFileComplete:
		var req protocol.FileComplete
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		s.transfers.Complete(req)

	case protocol.ActionFileControl:
		var req protocol.FileControl
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.transfers.Control(req); err != nil {
			s.fail(c, env, err)
		}

	case protocol.ActionFileList:
		history, err := s.transferHistory()
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, protocol.FileHistory{Transfers: history})

	case protocol.ActionSystemLock:
		if err := s.control.Lock(); err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, map[string]string{"status": "locked"})
		s.Broadcast()

	case protocol.ActionUnlockEnroll:
		var req protocol.UnlockEnroll
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.enrollUnlock(c, req); err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, map[string]string{"status": "enrolled"})

	case protocol.ActionUnlockChallenge:
		challenge, err := s.issueUnlockChallenge(c)
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, protocol.UnlockChallenge{Challenge: challenge})

	case protocol.ActionSystemUnlock:
		var req protocol.UnlockProof
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.unlock(c, req); err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, map[string]string{"status": "unlocked"})
		// The lock screen takes a moment to fall; the broadcast that follows
		// reports whatever it finds, and the poller in server.go corrects it.
		s.Broadcast()

	case protocol.ActionDeckGet:
		config, err := s.store.DeckConfig()
		if err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, config)

	case protocol.ActionDeckSet:
		var req protocol.DeckConfig
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.store.SaveDeckConfig(req); err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, req)
		s.BroadcastDeckState(req)

	case protocol.ActionDeckAction:
		var req protocol.DeckActionRequest
		if err := env.Decode(&req); err != nil {
			s.fail(c, env, err)
			return
		}
		if err := s.control.ExecuteDeckAction(req.Action); err != nil {
			s.fail(c, env, err)
			return
		}
		s.reply(c, env, map[string]string{"status": "ok"})

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
