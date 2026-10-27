// 在 node vm 中执行注入脚本，用伪造的 DOM 驱动各场景，输出 JSON 结果供 Go 测试断言。
const vm = require("vm");
const fs = require("fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const HARNESS = "https://dsh.example.test/app/";

function makeEnv(opts) {
    opts = opts || {};
    const env = {messages: [], assigned: [], listeners: []};
    const base = opts.baseTarget ? {getAttribute: () => opts.baseTarget} : null;
    const location = {href: HARNESS, origin: new URL(HARNESS).origin, assign: (u) => env.assigned.push(u)};
    const document = {
        baseURI: HARNESS,
        addEventListener: (type, fn, capture) => env.listeners.push({type, fn, capture}),
        querySelector: (sel) => (sel === "base[target]" ? base : null),
    };
    env.window = {_wails: {invoke: (m) => env.messages.push(m)}};
    vm.runInContext(script, vm.createContext({window: env.window, document, location, URL, setTimeout}));
    return env;
}

function link(attrs, xlink) {
    return {
        getAttribute: (n) => (n in attrs ? attrs[n] : null),
        getAttributeNS: (_ns, n) => (xlink && n in xlink ? xlink[n] : null),
    };
}

// dispatch 依次调用捕获阶段监听（注入脚本），再调用页面自己的处理器 page。
function dispatch(env, type, anchor, extra, page) {
    let prevented = !!(extra && extra.alreadyPrevented);
    const ev = {
        type,
        button: 0, ctrlKey: false, metaKey: false, shiftKey: false,
        target: {closest: () => anchor},
        preventDefault() { prevented = true; },
    };
    Object.assign(ev, extra || {});
    Object.defineProperty(ev, "defaultPrevented", {get: () => prevented});
    for (const l of env.listeners) if (l.type === type) l.fn(ev);
    if (page) page(ev, env);
    return () => prevented;
}

const scenarios = {
    "external link": (env) => dispatch(env, "click", link({href: "https://example.org/docs"})),
    "same-origin link": (env) => dispatch(env, "click", link({href: "/sessions/1"})),
    "same-origin blank": (env) => dispatch(env, "click", link({href: "/sessions/2", target: "_blank"})),
    "same-origin named target": (env) => dispatch(env, "click", link({href: "/sessions/3", target: "foo"})),
    "same-origin _top": (env) => dispatch(env, "click", link({href: "/sessions/4", target: "_top"})),
    "base target blank": (env) => dispatch(env, "click", link({href: "/sessions/5"})),
    "ctrl click same-origin": (env) => dispatch(env, "click", link({href: "/sessions/6"}), {ctrlKey: true}),
    "middle click external": (env) => dispatch(env, "auxclick", link({href: "https://example.org/middle"}), {button: 1}),
    "right auxclick ignored": (env) => dispatch(env, "auxclick", link({href: "https://example.org/right"}), {button: 2}),
    "svg xlink href": (env) => dispatch(env, "click", link({}, {href: "https://example.org/svg"})),
    "svg href": (env) => dispatch(env, "click", link({href: "https://example.org/svg2"})),
    "area": (env) => dispatch(env, "click", link({href: "https://example.org/area"})),
    "other port": (env) => dispatch(env, "click", link({href: "https://dsh.example.test:8443/"})),
    "mailto ignored": (env) => dispatch(env, "click", link({href: "mailto:a@example.org"})),
    "no link": (env) => dispatch(env, "click", null),
    "already prevented": (env) => dispatch(env, "click", link({href: "https://example.org/pre"}), {alreadyPrevented: true}),
    "page takes over click": (env) => dispatch(env, "click", link({href: "https://example.org/copy-only"}), {},
        (ev) => ev.preventDefault()),
    "page reopens via window.open": (env) => dispatch(env, "click", link({href: "https://example.org/dbl"}), {},
        (ev, e) => { ev.preventDefault(); e.window.open("https://example.org/dbl"); }),
    "open external": (env) => env.window.open("https://example.org/popup"),
    "open same-origin": (env) => env.window.open("/sessions/7"),
    "open no args": (env) => env.window.open(),
    "open empty": (env) => env.window.open(""),
    "open about blank": (env) => env.window.open("about:blank"),
    "open file": (env) => env.window.open("file:///etc/passwd"),
    "open javascript": (env) => env.window.open("javascript:alert(1)"),
};

(async () => {
    const results = {};
    for (const [name, run] of Object.entries(scenarios)) {
        const env = makeEnv(name === "base target blank" ? {baseTarget: "_blank"} : {});
        const out = run(env);
        await new Promise((r) => setTimeout(r, 5));
        results[name] = {
            messages: env.messages,
            assigned: env.assigned,
            prevented: typeof out === "function" ? out() : false,
            openReturn: typeof out === "function" ? undefined : out,
        };
    }
    // 脚本只安装 click 与 auxclick 两个监听。
    const env = makeEnv();
    results["listeners"] = {count: env.listeners.length};
    process.stdout.write(JSON.stringify(results));
})();
