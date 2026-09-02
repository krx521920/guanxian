export interface LatestRequestGate {
  begin(): number
  isCurrent(epoch: number): boolean
  invalidate(): void
}

export function createLatestRequestGate(): LatestRequestGate {
  let currentEpoch = 0

  return {
    begin() {
      currentEpoch += 1
      return currentEpoch
    },
    isCurrent(epoch) {
      return epoch === currentEpoch
    },
    invalidate() {
      currentEpoch += 1
    },
  }
}
