/** Outbound links, in one place so they can be changed without touching the copy. */
const repo = 'https://github.com/joachimhs/lyngvi';

export const links = {
	repo,
	releases: `${repo}/releases/latest`,
	readme: `${repo}#readme`,
	changelog: `${repo}/blob/main/CHANGELOG.md`,
	licence: `${repo}/blob/main/LICENSE`,
	docs: {
		README: `${repo}#readme`,
		'docs/agents.md': `${repo}/blob/main/docs/agents.md`,
		'docs/gateway-plugins.md': `${repo}/blob/main/docs/gateway-plugins.md`,
		'docs/profiles.md': `${repo}/blob/main/docs/profiles.md`,
		'acceptance/': `${repo}/tree/main/acceptance`
	}
};

export const version = '2.0.0';
