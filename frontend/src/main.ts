import {startConnecting, startLoadFailed} from "./loadfailed";
import {startReauth} from "./reauth";
import {showStartupError, startSetup} from "./setup";
import {startSettings} from "./settings";

const views: Record<string, () => Promise<void>> = {
    settings: startSettings,
    reauth: startReauth,
    connecting: startConnecting,
    'load-failed': startLoadFailed,
};
const view = new URLSearchParams(location.search).get('view') ?? '';
(views[view] ?? startSetup)().catch(showStartupError);
