package config

// Service 是绑定给本地初始化界面与设置界面的最小 Wails 服务。
type Service struct {
	store *Store
}

// NewService 返回使用 store 读写配置的 Service。
func NewService(store *Store) *Service {
	return &Service{store: store}
}

// CurrentURL 返回已保存的干净地址；尚未配置或配置无效时返回空字符串。
func (s *Service) CurrentURL() string {
	u, err := s.store.Load()
	if err != nil {
		return ""
	}
	return u
}

// ValidateURL 校验 raw，返回将会被保存的干净地址。
func (s *Service) ValidateURL(raw string) (string, error) {
	return Normalize(raw)
}

// SaveURL 校验并保存 raw，返回已保存的干净地址。
func (s *Service) SaveURL(raw string) (string, error) {
	return s.store.Save(raw)
}
