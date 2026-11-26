import Foundation

/// 团队页列表与分组组装器（与 `TeamHome.kt` 对齐）。
public enum TeamHome: Sendable {
    public struct Entry: Equatable, Sendable {
        public enum Kind: Equatable, Sendable {
            case bot
            case room
        }

        public let id: String
        public let kind: Kind
        public let name: String
        public let color: String?
        public let avatar: String?
        public let role: String?
        public let isMain: Bool
        public let isPrivate: Bool
        public let working: Bool
        public let lastSummary: String?
        public let pinned: Bool
        public let sessionId: String?
        public let lastAt: Int64?
        public let pendingKinds: [PendingItem.Kind]
        public let parts: [String]
        public let sectionId: String?
        public let members: [String]
        public let preview: TeamPreview.Preview?

        public init(
            id: String,
            kind: Kind,
            name: String,
            color: String?,
            avatar: String?,
            role: String?,
            isMain: Bool,
            isPrivate: Bool,
            working: Bool,
            lastSummary: String?,
            pinned: Bool,
            sessionId: String?,
            lastAt: Int64? = nil,
            pendingKinds: [PendingItem.Kind] = [],
            parts: [String] = [],
            sectionId: String? = nil,
            members: [String] = [],
            preview: TeamPreview.Preview? = nil
        ) {
            self.id = id
            self.kind = kind
            self.name = name
            self.color = color
            self.avatar = avatar
            self.role = role
            self.isMain = isMain
            self.isPrivate = isPrivate
            self.working = working
            self.lastSummary = lastSummary
            self.pinned = pinned
            self.sessionId = sessionId
            self.lastAt = lastAt
            self.pendingKinds = pendingKinds
            self.parts = parts
            self.sectionId = sectionId
            self.members = members
            self.preview = preview
        }

        public func withSectionId(_ sec: String?) -> Entry {
            Entry(
                id: id, kind: kind, name: name, color: color, avatar: avatar, role: role,
                isMain: isMain, isPrivate: isPrivate, working: working, lastSummary: lastSummary,
                pinned: pinned, sessionId: sessionId, lastAt: lastAt, pendingKinds: pendingKinds,
                parts: parts, sectionId: sec, members: members, preview: preview
            )
        }

        public func withPendingKinds(_ kinds: [PendingItem.Kind]) -> Entry {
            Entry(
                id: id, kind: kind, name: name, color: color, avatar: avatar, role: role,
                isMain: isMain, isPrivate: isPrivate, working: working, lastSummary: lastSummary,
                pinned: pinned, sessionId: sessionId, lastAt: lastAt, pendingKinds: kinds,
                parts: parts, sectionId: sectionId, members: members, preview: preview
            )
        }
    }

    public struct Section: Equatable, Sendable {
        public let id: String?
        public let name: String
        public let entries: [Entry]
        public let collapsed: Bool

        public init(id: String?, name: String, entries: [Entry], collapsed: Bool = false) {
            self.id = id
            self.name = name
            self.entries = entries
            self.collapsed = collapsed
        }
    }

    public struct PendingItem: Equatable, Sendable {
        public enum Kind: CaseIterable, Equatable, Sendable {
            case approval
            case question
            case view
        }

        public let kind: Kind
        public let sessionId: String?
        public let botId: String?
        public let summary: String?

        public init(kind: Kind, sessionId: String?, botId: String?, summary: String?) {
            self.kind = kind
            self.sessionId = sessionId
            self.botId = botId
            self.summary = summary
        }
    }

    public struct Team: Equatable, Sendable {
        public let sections: [Section]
        public let isEmpty: Bool

        public init(sections: [Section], isEmpty: Bool) {
            self.sections = sections
            self.isEmpty = isEmpty
        }
    }

    public static func assemble(_ state: DsbotModels.State) -> Team {
        let working = workingBotIds(state)
        let botEntries = state.bots.map { botEntry($0, state: state, working: working) }
        let roomEntries = state.rooms.map { roomEntry($0, state: state, working: working) }
        let allEntries = applyPending(botEntries + roomEntries, pending: pendingOf(state))

        let defined = TeamSections.decode(state.raw)
        let definedIds = Set(defined.map(\.id))
        let legacy = legacySectionMap(state.raw.array("sections"))

        let sorted = allEntries.sorted { a, b in
            if a.isMain != b.isMain { return a.isMain }
            if a.pinned != b.pinned { return a.pinned }
            let la = a.lastAt ?? Int64.min
            let lb = b.lastAt ?? Int64.min
            if la != lb { return la > lb }
            return a.name < b.name
        }

        var ungrouped: [Entry] = []
        var grouped: [String: [Entry]] = [:]
        for entry in sorted {
            let candidate = entry.isMain ? nil : (entry.sectionId ?? legacy[entry.id])
            let resolved = candidate.flatMap { definedIds.contains($0) ? $0 : nil }
            let updated = entry.withSectionId(resolved)
            if let resolved {
                grouped[resolved, default: []].append(updated)
            } else {
                ungrouped.append(updated)
            }
        }

        var resultSections: [Section] = []
        if !ungrouped.isEmpty {
            resultSections.append(Section(id: nil, name: defaultSectionName(nil), entries: ungrouped))
        }
        for sec in defined {
            resultSections.append(Section(
                id: sec.id,
                name: sec.name,
                entries: grouped[sec.id] ?? [],
                collapsed: sec.collapsed
            ))
        }
        return Team(sections: resultSections, isEmpty: allEntries.isEmpty)
    }

    public static func search(_ team: Team, query: String) -> Team {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty { return team }
        let filtered = team.sections.compactMap { sec -> Section? in
            let kept = sec.entries.filter { e in
                e.name.range(of: q, options: .caseInsensitive) != nil ||
                    (e.lastSummary?.range(of: q, options: .caseInsensitive) != nil) ||
                    (e.role?.range(of: q, options: .caseInsensitive) != nil)
            }
            return kept.isEmpty ? nil : Section(id: sec.id, name: sec.name, entries: kept, collapsed: sec.collapsed)
        }
        return Team(sections: filtered, isEmpty: team.isEmpty)
    }

    private static func botEntry(_ bot: DsbotModels.Bot, state: DsbotModels.State, working: Set<String>) -> Entry {
        Entry(
            id: bot.id,
            kind: .bot,
            name: bot.raw.nonEmptyString("name") ?? bot.id,
            color: bot.raw.nonEmptyString("color"),
            avatar: avatarOf(bot.raw),
            role: bot.raw.nonEmptyString("role"),
            isMain: state.mainBotIds.contains(bot.id),
            isPrivate: bot.raw.bool("private") ?? false,
            working: working.contains(bot.id),
            lastSummary: TeamPreview.clean(bot.raw.nonEmptyString("preview")),
            pinned: bot.raw.bool("pinned") ?? false,
            sessionId: bot.sessionId,
            lastAt: bot.raw.int64("lastAt"),
            parts: bot.parts,
            sectionId: bot.raw.nonEmptyString("sectionId"),
            preview: TeamPreview.ofBot(bot, state: state)
        )
    }

    private static func roomEntry(_ room: DsbotModels.Room, state: DsbotModels.State, working: Set<String>) -> Entry {
        Entry(
            id: room.id,
            kind: .room,
            name: room.raw.nonEmptyString("name") ?? room.id,
            color: room.raw.nonEmptyString("color"),
            avatar: avatarOf(room.raw),
            role: nil,
            isMain: false,
            isPrivate: false,
            working: room.members.contains { working.contains($0) },
            lastSummary: TeamPreview.clean(room.raw.nonEmptyString("preview")),
            pinned: room.raw.bool("pinned") ?? false,
            sessionId: room.sessionId,
            lastAt: room.raw.int64("lastAt"),
            sectionId: room.raw.nonEmptyString("sectionId"),
            members: room.members,
            preview: TeamPreview.ofRoom(room, state: state)
        )
    }

    private static func legacySectionMap(_ sections: [JsonValue]?) -> [String: String] {
        guard let sections else { return [:] }
        var map: [String: String] = [:]
        for sec in sections {
            guard let obj = sec.objectValue,
                  let id = obj.nonEmptyString("id"),
                  let items = obj.array("itemIds")
            else { continue }
            for item in items {
                if let itemId = item.stringValue {
                    map[itemId] = id
                }
            }
        }
        return map
    }

    private static func defaultSectionName(_ id: String?) -> String {
        id == nil ? I18n.tr("未分组", "Ungrouped") : I18n.tr("未命名分组", "Untitled section")
    }

    private static func workingBotIds(_ state: DsbotModels.State) -> Set<String> {
        guard let bots = state.raw.object("work")?.object("bots") else { return [] }
        return Set(bots.keys)
    }

    private static func applyPending(_ entries: [Entry], pending: [PendingItem]) -> [Entry] {
        guard !pending.isEmpty else { return entries }
        var byBotId: [String: Set<PendingItem.Kind>] = [:]
        var bySessionId: [String: Set<PendingItem.Kind>] = [:]
        for p in pending {
            if let bid = p.botId {
                byBotId[bid, default: []].insert(p.kind)
            }
            if let sid = p.sessionId {
                bySessionId[sid, default: []].insert(p.kind)
            }
        }
        return entries.map { e in
            var kinds = Set<PendingItem.Kind>()
            if let b = byBotId[e.id] { kinds.formUnion(b) }
            if let sid = e.sessionId, let s = bySessionId[sid] { kinds.formUnion(s) }
            if kinds.isEmpty { return e }
            let ordered = PendingItem.Kind.allCases.filter { kinds.contains($0) }
            return e.withPendingKinds(ordered)
        }
    }

    private static func avatarOf(_ raw: JsonObject) -> String? {
        raw.object("look")?.nonEmptyString("image")
    }

    private static func pendingOf(_ state: DsbotModels.State) -> [PendingItem] {
        var out: [PendingItem] = []
        if let approvals = state.raw.object("work")?.array("approvals") {
            for a in approvals {
                guard let obj = a.objectValue else { continue }
                out.append(PendingItem(
                    kind: .approval,
                    sessionId: obj.nonEmptyString("sessionId"),
                    botId: obj.nonEmptyString("botId"),
                    summary: obj.nonEmptyString("title") ?? obj.nonEmptyString("text") ?? I18n.tr("待审批", "Awaiting approval")
                ))
            }
        }
        if let questions = state.raw.object("questions") {
            for (sessionId, q) in questions.entries {
                guard let obj = q.objectValue else { continue }
                out.append(PendingItem(
                    kind: .question,
                    sessionId: sessionId,
                    botId: obj.nonEmptyString("botId"),
                    summary: obj.nonEmptyString("question") ?? I18n.tr("待回答", "Awaiting answer")
                ))
            }
        }
        if let views = state.raw.object("views") {
            for (sessionId, v) in views.entries {
                guard let obj = v.objectValue else { continue }
                out.append(PendingItem(
                    kind: .view,
                    sessionId: sessionId,
                    botId: obj.nonEmptyString("botId"),
                    summary: obj.nonEmptyString("title") ?? I18n.tr("待处理卡片", "Pending card")
                ))
            }
        }
        return out
    }
}
