// battery_curve_check.groovy — validates the CR1632 linear curve constants.
double[] V   = [2.85d, 3.00d] as double[]
double[] P   = [ 0.0d, 100.0d] as double[]
int pct(double v, double[] cv, double[] cp) {
    int n = cv.length
    if (v <= cv[0]) return 0
    if (v >= cv[n-1]) return 100
    for (int i=1;i<n;i++){ if (v<=cv[i]){ double lo=cv[i-1],hi=cv[i],pl=cp[i-1],ph=cp[i]; return (int)Math.round(pl+(v-lo)*(ph-pl)/(hi-lo)) } }
    return 100
}
assert pct(2.80d,V,P) == 0    : "below min clamps to 0"
assert pct(2.85d,V,P) == 0    : "min = 0%"
assert pct(3.00d,V,P) == 100  : "max = 100%"
assert pct(3.175d,V,P) == 100 : "above max clamps to 100 (observed units)"
assert pct(2.925d,V,P) == 50  : "midpoint = 50%"
println "battery_curve_check: OK"
