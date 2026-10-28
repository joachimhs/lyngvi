import { browser } from '$app/env';
import { content } from './content.js';

const KEY = 'lyngvi-lang';

/** @type {'en' | 'no'} */
let current = $state('en');

/** Prerendered HTML is English; the stored or browser preference is applied on mount. */
export const i18n = {
	get lang() {
		return current;
	},
	get t() {
		return content[current];
	},
	/** @param {'en' | 'no'} value */
	set(value) {
		if (value !== 'en' && value !== 'no') return;
		current = value;
		if (!browser) return;
		localStorage.setItem(KEY, value);
		document.documentElement.lang = value === 'no' ? 'nb' : 'en';
	},
	toggle() {
		this.set(current === 'en' ? 'no' : 'en');
	},
	restore() {
		if (!browser) return;
		const stored = localStorage.getItem(KEY);
		if (stored === 'en' || stored === 'no') {
			this.set(stored);
			return;
		}
		if (
			navigator.language?.toLowerCase().startsWith('nb') ||
			navigator.language?.toLowerCase().startsWith('nn') ||
			navigator.language?.toLowerCase().startsWith('no')
		) {
			this.set('no');
		}
	}
};
