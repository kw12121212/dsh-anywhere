import {Service as SettingsService} from "../bindings/dsh-anywhere/internal/settings";
import {errorMessage} from "./validation";

const placeholder = document.getElementById('placeholder') as HTMLElement;
const section = document.getElementById('reauth') as HTMLElement;
const openButton = document.getElementById('open-settings') as HTMLButtonElement;
const errorText = document.getElementById('reauth-error') as HTMLParagraphElement;

export async function startReauth(): Promise<void> {
    document.title = '需要重新认证 - dsh-anywhere';
    placeholder.hidden = true;
    section.hidden = false;
    openButton.addEventListener('click', () => {
        errorText.textContent = '';
        SettingsService.Open().catch((err) => {
            errorText.textContent = errorMessage(err);
        });
    });
    openButton.focus();
}
