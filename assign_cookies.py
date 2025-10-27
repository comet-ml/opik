class Solution:
    def findContentChildren(self, g: List[int], s: List[int]) -> int:
        n = len(g)
        m = len(s)
        
        s.sort()
        g.sort()

        l = 0 
        r = 0 

        while l < m and r < n:
            if s[l] >= g[r] :
                r += 1 
            l += 1 
        
        return r 
