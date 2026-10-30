import { test } from 'node:test'
import assert from 'node:assert/strict'
import { routeFromEvents } from './routes.js'

test('route survives reload and uses latest validated event', () => {
  const events = [
    { type: 'ROUTE_STARTED', detail: '{}' },
    { type: 'ROUTE_DECIDED', detail: '{"target":"WEB","origin":"RULE"}' },
    { type: 'ROUTE_DECIDED', detail: '{"target":"HYBRID","origin":"MODEL"}' },
  ]
  assert.equal(routeFromEvents(events).target, 'HYBRID')
})

test('invalid or absent route is not invented', () => {
  assert.equal(routeFromEvents([]), null)
  assert.equal(routeFromEvents([{ type: 'ROUTE_DECIDED', detail: 'bad' }]), null)
  assert.equal(routeFromEvents([{ type: 'ROUTE_DECIDED', detail: '{"target":"SHELL"}' }]), null)
})
