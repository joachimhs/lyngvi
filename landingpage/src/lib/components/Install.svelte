<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import { links } from '#lib/links.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
	import Shot from './Shot.svelte';

	const commands = [
		[
			'mkdir -p ~/sandboxes/shop && cd ~/sandboxes/shop',
			'# setup.sh: github.com/joachimhs/lyngvi/releases',
			'./setup.sh --download'
		],
		['./setup.sh init --repo ~/src/shop'],
		['./setup.sh rebuild-config', './setup.sh build', './setup.sh up', './setup.sh doctor'],
		[
			'./setup.sh agent login claude-code',
			'./setup.sh session new --title "Fix the login redirect"',
			'./setup.sh session ask 7f3a2c',
			'./setup.sh session review 7f3a2c',
			'./setup.sh session apply 7f3a2c --branch agent/fix-login'
		]
	];

	/** @param {string} label */
	const docHref = (label) =>
		/** @type {Record<string, string>} */ (links.docs)[label] ?? links.repo;

	let copied = $state(-1);

	/** @param {number} idx */
	async function copy(idx) {
		try {
			await navigator.clipboard.writeText(
				commands[idx].filter((l) => !l.startsWith('#')).join('\n')
			);
			copied = idx;
			setTimeout(() => (copied = copied === idx ? -1 : copied), 1600);
		} catch {
			/* clipboard blocked — the text is on screen anyway */
		}
	}
</script>

<section id="install" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.install.kicker}
			title={i18n.t.install.title}
			lead={i18n.t.install.lead}
		/>

		<p class="req">{i18n.t.install.requirements}</p>

		<!-- The app is the route most people take, so it comes first; the commands
		     below are the same sandbox made in a terminal. -->
		<Reveal delay={60}>
			<div class="gui frame">
				<div class="gui-inner">
					<h3 class="route">{i18n.t.install.guiTitle}</h3>
					<p class="gui-lead">{i18n.t.install.guiLead}</p>
					<ol class="wizard">
						{#each i18n.t.install.guiSteps as step, idx (step.title)}
							<li>
								<span class="w-n mono" aria-hidden="true">{idx + 1}</span>
								<strong>{step.title}</strong>
								<span>{step.body}</span>
							</li>
						{/each}
					</ol>

					<!-- 780 x 460 is the dialog's own size, so at this width the 2x image is pixel for pixel. -->
					<div class="gui-shot">
						<Shot
							name="wizard"
							width={780}
							height={460}
							title="New sandbox"
							alt={i18n.t.install.guiShot.alt}
							caption={i18n.t.install.guiShot.caption}
						/>
					</div>

					<p class="gui-note">{i18n.t.install.guiNote}</p>
					<a class="gui-more" href="#screens">{i18n.t.install.guiLink}</a>
				</div>
			</div>
		</Reveal>

		<h3 class="route">{i18n.t.install.cliTitle}</h3>

		<div class="steps">
			{#each i18n.t.install.steps as step, idx (step.title)}
				<Reveal delay={idx * 60}>
					<article class="step">
						<div class="copyblock">
							<span class="n num" aria-hidden="true">{String(idx + 1).padStart(2, '0')}</span>
							<h3>{step.title}</h3>
							<p>{step.body}</p>
						</div>
						<div class="cmdblock">
							<button class="copy" type="button" onclick={() => copy(idx)}>
								{copied === idx ? '✓' : '⧉'}
							</button>
							<!-- prettier-ignore -->
							<pre><code>{#each commands[idx] as line, i (i)}<span class="line" class:comment={line.startsWith('#')}>{line}</span>{/each}</code></pre>
						</div>
					</article>
				</Reveal>
			{/each}
		</div>

		<Reveal delay={80}>
			<div class="docs frame">
				<div class="docs-inner">
					<h3 class="route">{i18n.t.install.docsTitle}</h3>
					<ul>
						{#each i18n.t.install.docs as doc (doc.label)}
							<li>
								<a class="mono" href={docHref(doc.label)} rel="noreferrer noopener">{doc.label}</a>
								<span>{doc.note}</span>
							</li>
						{/each}
					</ul>
					<div class="cta">
						<a class="btn btn-primary" href={links.releases} rel="noreferrer noopener">Releases</a>
						<a class="btn" href={links.repo} rel="noreferrer noopener">{i18n.t.nav.source}</a>
					</div>
				</div>
			</div>
		</Reveal>
	</div>
</section>

<style>
	.req {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		margin-bottom: 1.5rem;
		font-family: var(--mono);
		font-size: 0.74rem;
		letter-spacing: 0.02em;
		color: var(--dim);
	}

	.req::before {
		content: '';
		flex: none;
		width: 6px;
		height: 6px;
		background: var(--warn);
		box-shadow: 0 0 10px 1px rgba(245, 192, 78, 0.5);
	}

	/* Two routes to the same sandbox: this label sits above the commands, and the
	   same one heads the app panel below them. */
	.route {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		font-family: var(--mono);
		font-size: 0.72rem;
		font-weight: 500;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--cyan);
	}

	.route::before {
		content: '';
		flex: none;
		width: 6px;
		height: 6px;
		background: var(--cyan);
		box-shadow: 0 0 10px 1px rgba(34, 211, 238, 0.55);
	}

	.steps {
		display: grid;
		gap: 0;
		margin-top: 0.9rem;
		border-top: 1px solid var(--line);
	}

	/* Two columns: the numbered copy on the left, the commands on the right.
	   The number gets its own gutter inside the copy block. */
	.step {
		display: grid;
		grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
		gap: 2rem;
		padding: 1.8rem 0;
		align-items: center;
		border-bottom: 1px solid var(--line);
	}

	.copyblock {
		display: grid;
		grid-template-columns: 4rem minmax(0, 1fr);
		column-gap: 0.5rem;
		align-items: start;
	}

	.n {
		grid-row: 1 / span 2;
		font-family: var(--serif);
		font-size: 2.6rem;
		line-height: 1;
		letter-spacing: -0.03em;
		color: var(--cyan);
		opacity: 0.6;
		translate: 0 -0.1em;
	}

	.step h3 {
		grid-column: 2;
		font-size: 1.15rem;
	}

	.step p {
		grid-column: 2;
		margin-top: 0.5rem;
		color: var(--dim);
		font-size: 0.9rem;
		max-width: 40ch;
	}

	.cmdblock {
		position: relative;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(3, 8, 15, 0.85);
		box-shadow: var(--shadow);
	}

	.cmdblock pre {
		margin: 0;
		padding: 0.95rem 2.8rem 0.95rem 1.1rem;
		overflow-x: auto;
	}

	.line {
		display: block;
		font-size: 0.77rem;
		line-height: 1.85;
		color: var(--text);
		white-space: pre;
	}

	/* The prompt is drawn by CSS so the markup inside <pre> stays whitespace-exact. */
	.line:not(.comment)::before {
		content: '$ ';
		color: var(--cyan);
	}

	.line.comment {
		color: var(--faint);
	}

	.copy {
		position: absolute;
		top: 0.5rem;
		right: 0.5rem;
		width: 1.8rem;
		height: 1.8rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(10, 20, 34, 0.9);
		color: var(--dim);
		font-size: 0.8rem;
		line-height: 1;
		cursor: pointer;
		transition:
			color 0.15s ease,
			border-color 0.15s ease;
	}

	.copy:hover {
		color: var(--cyan);
		border-color: var(--cyan);
	}

	/* The app route: the wizard's steps, in the same panel the documents get.
	   It is the first of the two routes, so the spacing is below it. */
	.gui {
		margin-bottom: 2.75rem;
	}

	.gui-inner {
		padding: 1.6rem 1.5rem;
		border: 1px solid var(--line);
		background: linear-gradient(180deg, rgba(10, 20, 34, 0.7), rgba(5, 11, 20, 0.4));
	}

	.gui-lead {
		margin: 1rem 0 0;
		max-width: 68ch;
		color: var(--dim);
		font-size: 0.9rem;
	}

	.wizard {
		margin: 1.4rem 0 0;
		padding: 0;
		list-style: none;
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 1px;
		background: var(--line);
		border: 1px solid var(--line);
	}

	/* The cards are stretched to the tallest; without this the spare height would be
	   shared between the title row and the body row instead of falling to the bottom. */
	.wizard li {
		display: grid;
		grid-template-columns: 1.4rem minmax(0, 1fr);
		align-content: start;
		row-gap: 0.3rem;
		padding: 0.9rem 1rem;
		background: rgba(5, 11, 20, 0.6);
	}

	.w-n {
		grid-row: 1 / span 2;
		font-size: 0.72rem;
		line-height: 1.35;
		color: var(--cyan);
		opacity: 0.75;
	}

	.wizard strong {
		font-size: 0.88rem;
		font-weight: 600;
	}

	.wizard li span:not(.w-n) {
		grid-column: 2;
		color: var(--faint);
		font-size: 0.82rem;
		line-height: 1.5;
	}

	.gui-shot {
		max-width: 780px;
		margin: 1.4rem auto 0;
	}

	.gui-note {
		margin: 1.2rem 0 0;
		max-width: 68ch;
		color: var(--faint);
		font-size: 0.82rem;
	}

	.gui-more {
		display: inline-block;
		margin-top: 0.9rem;
		font-size: 0.82rem;
		text-decoration: none;
	}

	.gui-more::after {
		content: ' →';
	}

	.docs {
		margin-top: 2.5rem;
	}

	.docs-inner {
		padding: 1.6rem 1.5rem;
		border: 1px solid var(--line);
		background: linear-gradient(180deg, rgba(10, 20, 34, 0.7), rgba(5, 11, 20, 0.4));
	}

	.docs ul {
		margin: 1rem 0 0;
		padding: 0;
		list-style: none;
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
		gap: 0 2rem;
	}

	.docs li {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0.1rem 0.6rem;
		padding: 0.55rem 0;
		border-bottom: 1px solid var(--line);
	}

	.docs li a {
		font-size: 0.8rem;
		text-decoration: none;
	}

	.docs li span {
		color: var(--faint);
		font-size: 0.82rem;
	}

	.cta {
		display: flex;
		flex-wrap: wrap;
		gap: 0.7rem;
		margin-top: 1.5rem;
	}

	@media (max-width: 820px) {
		.step {
			grid-template-columns: minmax(0, 1fr);
			gap: 1rem;
		}

		.copyblock {
			grid-template-columns: 3rem minmax(0, 1fr);
		}

		.n {
			font-size: 2rem;
		}

		.wizard {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}
	}

	@media (max-width: 520px) {
		.wizard {
			grid-template-columns: minmax(0, 1fr);
		}
	}
</style>
