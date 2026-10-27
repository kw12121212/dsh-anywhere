import {Service as ConfigService} from "../bindings/dsh-anywhere/internal/config";

export function errorMessage(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
}

export interface Validation {
    // 按当前输入重新校验并更新错误文字与按钮状态。
    validate(): Promise<void>;
    // 作废在途的校验，避免其结果在提交期间重新启用按钮。
    invalidate(): void;
}

// bindValidation 在输入时实时校验地址：只采用最后一次输入的结果；空输入时禁用按钮且不显示错误。
export function bindValidation(input: HTMLInputElement, errorText: HTMLElement, button: HTMLButtonElement): Validation {
    let generation = 0;

    async function validate(): Promise<void> {
        const current = ++generation;
        const raw = input.value;
        if (raw.trim() === '') {
            errorText.textContent = '';
            button.disabled = true;
            return;
        }
        try {
            await ConfigService.ValidateURL(raw);
            if (current !== generation) return;
            errorText.textContent = '';
            button.disabled = false;
        } catch (err) {
            if (current !== generation) return;
            errorText.textContent = errorMessage(err);
            button.disabled = true;
        }
    }

    input.addEventListener('input', () => void validate());
    return {validate, invalidate: () => void ++generation};
}
