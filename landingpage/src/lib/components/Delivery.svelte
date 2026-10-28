<script>
	import { i18n } from '#lib/i18n.svelte.js';
	import SectionHead from './SectionHead.svelte';
	import Reveal from './Reveal.svelte';
	import Shot from './Shot.svelte';

	const buckets = /** @type {const} */ (['apply', 'hold', 'reject']);

	/** Paths of held files the reviewer has decided to include with --include. */
	let included = $state(new Set());

	/** @param {string} path */
	function toggle(path) {
		const next = new Set(included);
		if (next.has(path)) next.delete(path);
		else next.add(path);
		included = next;
	}

	/** @type {{path: string, change: string, bucket: 'apply'|'hold'|'reject', stat: string, reason?: string}[]} */
	let files = $derived(/** @type {any} */ (i18n.t.delivery.files));
	let count = $derived(
		files.filter((f) => f.bucket === 'apply').length +
			files.filter((f) => f.bucket === 'hold' && included.has(f.path)).length
	);
	let includeFlags = $derived([...included].map((path) => `--include ${path}`).join(' '));
	let command = $derived(
		`./setup.sh ${i18n.t.delivery.applyLabel} ${i18n.t.delivery.branch}` +
			(includeFlags ? ' ' + includeFlags : '')
	);
</script>

{#snippet mark(/** @type {boolean} */ picked, /** @type {string} */ bucket)}
	{#if picked}
		<svg
			viewBox="0 0 16 16"
			width="11"
			height="11"
			fill="none"
			stroke="currentColor"
			stroke-width="2.6"
			stroke-linecap="round"
			stroke-linejoin="round"><path d="M3 8.5 6.5 12 13 4.5" /></svg
		>
	{:else if bucket === 'reject'}
		<svg
			viewBox="0 0 16 16"
			width="11"
			height="11"
			fill="none"
			stroke="currentColor"
			stroke-width="2.6"
			stroke-linecap="round"><path d="M4 4l8 8M12 4l-8 8" /></svg
		>
	{/if}
{/snippet}

<section id="delivery" class="section">
	<div class="page">
		<SectionHead
			kicker={i18n.t.delivery.kicker}
			title={i18n.t.delivery.title}
			lead={i18n.t.delivery.lead}
		/>

		<Reveal>
			<ol class="steps">
				{#each i18n.t.delivery.steps as step, idx (step.title)}
					<li>
						<span class="n num">{String(idx + 1).padStart(2, '0')}</span>
						<div class="step-text">
							<h3>{step.title}</h3>
							<p>{step.body}</p>
						</div>
					</li>
				{/each}
			</ol>
		</Reveal>

		<Reveal delay={80}>
			<div class="review frame">
				<div class="review-inner">
					<header class="bar">
						<span class="title mono">{i18n.t.delivery.tableTitle}</span>
						<span class="hint">{i18n.t.delivery.tableHint}</span>
					</header>

					<table>
						<thead>
							<tr>
								<th>{i18n.t.delivery.columns.file}</th>
								<th class="hide-sm">{i18n.t.delivery.columns.change}</th>
								<th>{i18n.t.delivery.columns.bucket}</th>
							</tr>
						</thead>
						<tbody>
							{#each files as file (file.path)}
								{@const picked = file.bucket === 'apply' || included.has(file.path)}
								<tr
									class={file.bucket}
									class:picked
									class:clickable={file.bucket === 'hold'}
									onclick={() => file.bucket === 'hold' && toggle(file.path)}
								>
									<td>
										{#if file.bucket === 'hold'}
											<button
												class="box"
												class:on={picked}
												type="button"
												aria-pressed={picked}
												aria-label="--include {file.path}"
												onclick={(event) => {
													event.stopPropagation();
													toggle(file.path);
												}}
											>
												{@render mark(picked, file.bucket)}
											</button>
										{:else}
											<span class="box locked" class:on={picked}>
												{@render mark(picked, file.bucket)}
											</span>
										{/if}
										<code>{file.path}</code>
										{#if file.reason}<span class="reason mono">{file.reason}</span>{/if}
									</td>
									<td class="hide-sm">
										<span class="change">{file.change}</span>
										<span class="stat mono">{file.stat}</span>
									</td>
									<td>
										<span class="bucket {file.bucket}">{i18n.t.delivery.buckets[file.bucket]}</span>
									</td>
								</tr>
							{/each}
						</tbody>
					</table>

					<footer class="apply">
						<code class="command">{command}</code>
						<p class="summary">{i18n.t.delivery.summary(count)}</p>
						<p class="protected">{i18n.t.delivery.protectedNote}</p>
					</footer>
				</div>
			</div>
		</Reveal>

		<Reveal delay={120}>
			<ul class="rules">
				{#each buckets as bucket (bucket)}
					<li class={bucket}>
						<span class="bucket {bucket}">{i18n.t.delivery.buckets[bucket]}</span>
						<p>{i18n.t.delivery.bucketRules[bucket]}</p>
					</li>
				{/each}
			</ul>
		</Reveal>

		<Reveal delay={60}>
			<div class="figure">
				<Shot
					name="x-review"
					width={1088}
					height={395}
					title="Deliver changes"
					alt={i18n.t.delivery.figure.alt}
					caption={i18n.t.delivery.figure.caption}
				/>
			</div>
		</Reveal>
	</div>
</section>

<style>
	.figure {
		margin-top: 2.5rem;
	}

	/* The four steps as one line of events with a rule running through them. */
	.steps {
		position: relative;
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
		gap: 1.5rem;
		margin: 0 0 2.5rem;
		padding: 1.4rem 0 0;
		list-style: none;
		border-top: 1px solid var(--line);
	}

	.steps li {
		position: relative;
		display: grid;
		gap: 0.6rem;
	}

	.steps li::before {
		content: '';
		position: absolute;
		top: calc(-1.4rem - 3px);
		left: 0;
		width: 5px;
		height: 5px;
		background: var(--cyan);
		box-shadow: 0 0 10px 1px rgba(34, 211, 238, 0.5);
	}

	.steps .n {
		font-family: var(--serif);
		font-size: 2rem;
		line-height: 1;
		letter-spacing: -0.03em;
		color: var(--cyan);
		opacity: 0.6;
	}

	.steps h3 {
		font-size: 0.98rem;
	}

	.steps p {
		margin-top: 0.35rem;
		color: var(--faint);
		font-size: 0.84rem;
		line-height: 1.5;
	}

	.review-inner {
		overflow: hidden;
		border: 1px solid var(--line);
		background: linear-gradient(180deg, rgba(10, 20, 34, 0.7), rgba(5, 11, 20, 0.5));
	}

	.bar {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 0.3rem 1rem;
		padding: 0.85rem 1.2rem;
		border-bottom: 1px solid var(--line);
		background: rgba(10, 20, 34, 0.7);
	}

	.bar .title {
		font-size: 0.72rem;
		font-weight: 500;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--cyan);
	}

	.bar .hint {
		font-size: 0.8rem;
		color: var(--faint);
	}

	table {
		width: 100%;
		border-collapse: collapse;
		font-size: 0.88rem;
	}

	th {
		padding: 0.6rem 1.2rem;
		text-align: left;
		font-family: var(--mono);
		font-size: 0.66rem;
		font-weight: 500;
		letter-spacing: 0.12em;
		text-transform: uppercase;
		color: var(--faint);
		border-bottom: 1px solid var(--line);
	}

	td {
		padding: 0.65rem 1.2rem;
		border-bottom: 1px solid rgba(142, 190, 222, 0.08);
		vertical-align: middle;
	}

	tbody tr {
		transition: background 0.15s ease;
	}

	tr.clickable {
		cursor: pointer;
	}

	tr.clickable:hover {
		background: rgba(142, 190, 222, 0.06);
	}

	tr.reject code {
		color: var(--bad);
		text-decoration: line-through;
		text-decoration-color: rgba(255, 122, 122, 0.5);
	}

	td code {
		font-size: 0.8rem;
		color: var(--text);
	}

	.box {
		display: inline-grid;
		place-items: center;
		width: 1.05rem;
		height: 1.05rem;
		padding: 0;
		margin-right: 0.6rem;
		translate: 0 0.15rem;
		border: 1px solid var(--line-strong);
		border-radius: 3px;
		background: transparent;
		color: #03111a;
		transition:
			background 0.15s ease,
			border-color 0.15s ease;
	}

	button.box {
		cursor: pointer;
	}

	.box.on {
		background: var(--ok);
		border-color: var(--ok);
	}

	tr.reject .box {
		border-color: rgba(255, 122, 122, 0.5);
		color: var(--bad);
	}

	.box.locked {
		opacity: 0.9;
	}

	.reason {
		margin-left: 0.5rem;
		font-size: 0.66rem;
		letter-spacing: 0.08em;
		color: var(--faint);
	}

	.change {
		color: var(--dim);
	}

	.stat {
		margin-left: 0.5rem;
		font-size: 0.74rem;
		color: var(--faint);
	}

	.bucket {
		display: inline-block;
		padding: 0.12rem 0.5rem;
		border-radius: var(--r-sm);
		font-family: var(--mono);
		font-size: 0.66rem;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		border: 1px solid transparent;
	}

	.bucket.apply {
		background: var(--ok-soft);
		color: var(--ok);
		border-color: rgba(61, 220, 151, 0.3);
	}

	.bucket.hold {
		background: var(--warn-soft);
		color: var(--warn);
		border-color: rgba(245, 192, 78, 0.3);
	}

	.bucket.reject {
		background: var(--bad-soft);
		color: var(--bad);
		border-color: rgba(255, 122, 122, 0.3);
	}

	footer.apply {
		padding: 1.1rem 1.2rem 1.2rem;
	}

	.command {
		display: block;
		padding: 0.65rem 0.85rem;
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		background: rgba(3, 8, 15, 0.8);
		color: var(--cyan);
		font-size: 0.78rem;
		overflow-x: auto;
		white-space: pre;
	}

	.summary {
		margin-top: 0.8rem;
		font-size: 0.92rem;
		color: var(--text);
	}

	.protected {
		margin-top: 0.25rem;
		font-size: 0.8rem;
		color: var(--faint);
	}

	.rules {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
		gap: 0 2rem;
		margin: 2.5rem 0 0;
		padding: 0;
		list-style: none;
		border-top: 1px solid var(--line);
	}

	.rules li {
		padding: 1.2rem 0 0;
	}

	.rules p {
		margin-top: 0.6rem;
		color: var(--dim);
		font-size: 0.86rem;
	}

	@media (max-width: 640px) {
		.hide-sm {
			display: none;
		}

		th,
		td {
			padding-inline: 0.9rem;
		}
	}
</style>
