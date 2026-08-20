/**
 * The study bot's wire vocabulary (Common tier, E16.11 — F12).
 *
 * <p>Two audiences share this package and the split between them is the whole
 * security story of F12.8/S-34:
 *
 * <ul>
 *   <li><b>the student</b> asks ({@link common.dto.bot.BotAskRequest} →
 *       {@link common.dto.bot.BotAnswer}) and reads her own history
 *       ({@link common.dto.bot.BotSessionsPage},
 *       {@link common.dto.bot.BotConversation}). None of her verbs carries a user
 *       id, because the caller is the session bound to the socket and an id in one
 *       of these payloads could only ever be a classmate's (P-5);</li>
 *   <li><b>the teacher</b> manages the bot
 *       ({@link common.dto.bot.BotManagerPage} and the four request records) and
 *       reads an aggregate that is anonymous by construction
 *       ({@link common.dto.bot.BotAnalytics}). The analytics types have no field
 *       that could hold an identity, which is S-34 as a shape rather than as a
 *       promise.</li>
 * </ul>
 *
 * <p>What is <b>not</b> here is as deliberate as what is. No exam, execution,
 * attempt or grade type appears anywhere in this package or is reachable from it:
 * the bot's context is course material and bank questions (S-28) and nothing
 * else, and the bank questions that do travel into a prompt carry their four
 * answers without marking which is correct (F12.8, lead's ruling). The wire
 * contract is frozen in {@code docs/contracts/BOT_WIRE_CONTRACT.md}.
 */
package common.dto.bot;
